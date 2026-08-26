"""Wires the graph (``graph.py``), the tool plan (``tools.py``), trace emission
(``trace.py``) and the idempotency checkpoint (``idempotency.py``) into one
run of the agent.

The state machine is intentionally small and linear (search -> fetch metric ->
summarize) rather than LLM-routed: a real agent would use its model to decide
which tool to call next and when to stop, which is exactly the part this
worker fakes (see ``run_spec.model`` - it is threaded through the state and
into trace events, but nothing here actually calls an LLM). What's real is
everything *around* that decision: the graph/edge control flow, per-step
tracing, per-step idempotency, and status reporting - which is what this
codebase's Kubernetes/streaming layer actually needs to exercise.
"""

from __future__ import annotations

from typing import Any, Callable, Optional

from .graph import END, CompiledGraph, StateGraph
from .idempotency import IdempotencyStore
from .run_spec import RunSpec
from .tools import DEFAULT_TOOL_PLAN, ToolError
from .trace import ToolTimer, TraceEmitter

ToolPlan = list[tuple[str, Callable[[dict], Any]]]


def build_graph(
    idempotency_store: IdempotencyStore,
    trace_emitter: TraceEmitter,
    tool_plan: ToolPlan = DEFAULT_TOOL_PLAN,
) -> CompiledGraph:
    """Builds: ``execute_step`` (looped via a conditional edge) -> ``finalize`` -> END.

    ``execute_step`` re-runs for as long as the router sends it back to itself;
    once every tool in ``tool_plan`` has run (or a step fails, or the run's own
    ``max_steps`` budget is exhausted) the router sends control to ``finalize``.
    """

    def execute_step(state: dict) -> dict:
        step = state["step"]
        name, fn = tool_plan[step]
        key = state["idempotency_key"]

        if idempotency_store.is_step_complete(key, step):
            checkpoint = idempotency_store.load(key)
            result = checkpoint.completed_steps[step]
            trace_emitter.step(step, "SKIPPED_ALREADY_COMPLETE", tool=name)
            state["tool_results"][name] = result
            state["step"] = step + 1
            return state

        trace_emitter.step(step, "STARTED", tool=name)
        try:
            with ToolTimer(trace_emitter, name):
                result = fn(state)
        except ToolError as exc:
            state["status"] = "FAILED"
            state["error"] = str(exc)
            trace_emitter.step(step, "FAILED", tool=name, error=str(exc))
            return state

        state["tool_results"][name] = result
        idempotency_store.record_step(key, step, result)
        state["step"] = step + 1
        trace_emitter.step(step, "COMPLETED", tool=name)
        return state

    def route(state: dict) -> str:
        if state["status"] == "FAILED":
            return "finalize"
        if state["step"] >= len(tool_plan) or state["step"] >= state["max_steps"]:
            return "finalize"
        return "execute_step"

    def finalize(state: dict) -> dict:
        if state["status"] != "FAILED":
            state["status"] = "SUCCEEDED"
        trace_emitter.log(
            "INFO",
            f"run finished with status {state['status']}",
            stepsCompleted=state["step"],
        )
        return state

    graph = StateGraph()
    graph.add_node("execute_step", execute_step)
    graph.add_node("finalize", finalize)
    graph.set_entry_point("execute_step")
    graph.add_conditional_edges("execute_step", route)
    graph.add_edge("finalize", END)
    # Safety valve on total node executions, independent of the run's own
    # max_steps: len(tool_plan) executions of execute_step plus one finalize,
    # with generous headroom - this is "the graph is malformed", not a normal exit.
    return graph.compile(max_steps=(len(tool_plan) + 1) * 2 + 10)


def initial_state(spec: RunSpec) -> dict:
    return {
        "run_id": spec.run_id,
        "prompt": spec.prompt,
        "model": spec.model,
        "max_steps": spec.max_steps,
        "idempotency_key": spec.idempotency_key,
        "step": 0,
        "tool_results": {},
        "status": "RUNNING",
        "error": None,
    }


def execute_run(
    spec: RunSpec,
    idempotency_store: IdempotencyStore,
    trace_emitter: TraceEmitter,
    tool_plan: ToolPlan = DEFAULT_TOOL_PLAN,
) -> dict:
    """Runs (or resumes) the agent for one run spec, honouring
    ``spec.idempotency_key`` end to end:

    - if a *previous* attempt already reached a terminal status for this key,
      this returns that recorded result immediately without touching the graph
      or any tool at all (the strongest form of "do not re-run completed
      work" - a fully-finished run is not even partially replayed);
    - otherwise the graph runs, and ``execute_step`` itself skips any
      individual step already checkpointed by a previous attempt (see
      ``build_graph``), so a worker that died after step 2 of 3 only re-runs
      step 3 on redelivery.
    """
    key = spec.idempotency_key
    if idempotency_store.is_finished(key):
        checkpoint = idempotency_store.load(key)
        trace_emitter.log(
            "INFO",
            "run already completed by a previous attempt - skipping re-execution",
            idempotencyKey=key,
            previousStatus=checkpoint.status,
        )
        return {
            "run_id": spec.run_id,
            "status": checkpoint.status,
            "error": (checkpoint.result or {}).get("error") if checkpoint.result else None,
            "tool_results": (checkpoint.result or {}).get("toolResults", {}),
            "step": len(checkpoint.completed_steps),
            "resumed": True,
        }

    graph = build_graph(idempotency_store, trace_emitter, tool_plan)
    final_state = graph.invoke(initial_state(spec))

    idempotency_store.record_finished(
        key,
        final_state["status"],
        {"toolResults": final_state["tool_results"], "error": final_state.get("error")},
    )
    final_state["resumed"] = False
    return final_state
