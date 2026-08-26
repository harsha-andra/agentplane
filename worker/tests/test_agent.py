"""Tests for the state machine (agent.py + graph.py + tools.py) and, crucially,
its integration with the idempotency checkpoint: a "redelivered job" is
simulated by constructing a *second*, independent ``IdempotencyStore`` /
``execute_run`` call pointed at the same checkpoint directory, exactly as a
replacement pod would after the original pod died.
"""

import io

from agentplane_worker.agent import execute_run
from agentplane_worker.idempotency import IdempotencyStore
from agentplane_worker.run_spec import RunSpec
from agentplane_worker.tools import ToolError
from agentplane_worker.trace import TraceEmitter


def _spec(idempotency_key="run-1", max_steps=10) -> RunSpec:
    return RunSpec(
        run_id="run-1",
        prompt="find the tool latency regression",
        model="test-model",
        max_steps=max_steps,
        idempotency_key=idempotency_key,
    )


def _emitter() -> TraceEmitter:
    emitter = TraceEmitter(run_id="run-1")
    emitter._stream = io.StringIO()  # keep test output quiet
    return emitter


def test_full_run_succeeds_and_calls_every_tool(tmp_path):
    store = IdempotencyStore(tmp_path)
    result = execute_run(_spec(), store, _emitter())

    assert result["status"] == "SUCCEEDED"
    assert result["resumed"] is False
    assert set(result["tool_results"].keys()) == {"search_docs", "fetch_metric", "summarize"}
    assert store.is_finished("run-1") is True
    assert store.load("run-1").status == "SUCCEEDED"


def test_max_steps_caps_execution_before_the_full_plan_runs(tmp_path):
    store = IdempotencyStore(tmp_path)
    result = execute_run(_spec(max_steps=1), store, _emitter())

    assert result["status"] == "SUCCEEDED"  # capping steps is not a failure
    assert result["step"] == 1
    assert set(result["tool_results"].keys()) == {"search_docs"}


def test_a_failing_tool_marks_the_run_failed_and_stops(tmp_path):
    calls = {"count": 0}

    def boom(_state):
        calls["count"] += 1
        raise ToolError("simulated tool failure")

    def never_called(_state):
        raise AssertionError("must not run a step after a failure")

    tool_plan = [("boom", boom), ("never_called", never_called)]
    store = IdempotencyStore(tmp_path)

    result = execute_run(_spec(), store, _emitter(), tool_plan=tool_plan)

    assert result["status"] == "FAILED"
    assert result["error"] == "simulated tool failure"
    assert calls["count"] == 1
    assert store.load("run-1").status == "FAILED"


def test_redelivered_job_does_not_repeat_a_completed_step(tmp_path):
    """The core idempotency contract: a step already checkpointed by a prior
    attempt is never re-executed by a later one, even though the later
    attempt has no in-memory knowledge of the first - only the checkpoint
    file on disk connects them, exactly as it would across two pods."""
    call_log: list[str] = []

    def step_a(_state):
        call_log.append("a")
        return {"n": 1}

    def step_b(_state):
        call_log.append("b")
        return {"n": 2}

    tool_plan = [("step_a", step_a), ("step_b", step_b)]

    # --- attempt 1: dies after completing step_a but before step_b -------------
    store_attempt_1 = IdempotencyStore(tmp_path)
    store_attempt_1.record_step("run-1", 0, {"n": 1})  # what step_a would have produced
    assert call_log == []  # step_a was never actually invoked in this "attempt"

    # --- attempt 2: a fresh IdempotencyStore instance, same directory (this is
    # the redelivered-message / replacement-pod case) --------------------------
    store_attempt_2 = IdempotencyStore(tmp_path)
    result = execute_run(_spec(), store_attempt_2, _emitter(), tool_plan=tool_plan)

    assert call_log == ["b"]  # step_a's callable was never invoked at all
    assert result["status"] == "SUCCEEDED"
    assert result["tool_results"]["step_a"] == {"n": 1}  # loaded from the checkpoint
    assert result["tool_results"]["step_b"] == {"n": 2}  # actually executed


def test_a_fully_finished_run_short_circuits_before_touching_any_tool(tmp_path):
    """Stronger than per-step skipping: if the run already reached a terminal
    status, a later attempt (e.g. a manual retry with the same idempotency
    key) must not invoke the graph - or any tool - at all."""

    def must_not_be_called(_state):
        raise AssertionError("a finished run must not re-execute any tool")

    tool_plan = [("must_not_be_called", must_not_be_called)]
    store = IdempotencyStore(tmp_path)
    store.record_finished("run-1", "SUCCEEDED", {"toolResults": {"cached": True}, "error": None})

    result = execute_run(_spec(), store, _emitter(), tool_plan=tool_plan)

    assert result["resumed"] is True
    assert result["status"] == "SUCCEEDED"
    assert result["tool_results"] == {"cached": True}


def test_different_idempotency_keys_do_not_share_checkpoints(tmp_path):
    store = IdempotencyStore(tmp_path)
    execute_run(_spec(idempotency_key="run-a"), store, _emitter())

    assert store.is_finished("run-a") is True
    assert store.is_finished("run-b") is False
