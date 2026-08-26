"""A tiny, dependency-free graph executor, deliberately shaped like LangGraph's
``StateGraph`` API (nodes are functions over a shared state dict, edges route
between them, ``END`` terminates), without importing the ``langgraph`` package.

Why not the real thing: ``langgraph`` pulls in ``langchain-core`` and a fairly
deep dependency tree for what this worker actually needs -- a handful of nodes,
one conditional loop, and a state dict. Reproducing the *shape* of its API here
keeps the worker's dependency footprint at "the Python standard library plus
``requests``" while still reading like a LangGraph agent to anyone who has
written one. If this worker's graph grows real branching/parallelism needs,
swapping this module for ``langgraph`` itself is a contained change -- nothing
outside this file knows the difference, because callers only ever see
``StateGraph`` / ``CompiledGraph`` / ``END``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, MutableMapping

State = Dict[str, Any]
NodeFn = Callable[[State], State]
"""A node receives the whole state dict and returns the (possibly mutated) state."""

END = "__end__"


class GraphError(Exception):
    """Raised for malformed graphs (unknown node referenced, no entry point, ...)."""


@dataclass
class StateGraph:
    """Builder for a small directed graph of state-transforming nodes."""

    _nodes: Dict[str, NodeFn] = field(default_factory=dict)
    _edges: Dict[str, str] = field(default_factory=dict)
    _conditional_edges: Dict[str, Callable[[State], str]] = field(default_factory=dict)
    _entry_point: str | None = None

    def add_node(self, name: str, fn: NodeFn) -> "StateGraph":
        self._nodes[name] = fn
        return self

    def set_entry_point(self, name: str) -> "StateGraph":
        self._entry_point = name
        return self

    def add_edge(self, start: str, end: str) -> "StateGraph":
        """Unconditional edge: after ``start`` runs, go to ``end`` (or ``END``)."""
        self._edges[start] = end
        return self

    def add_conditional_edges(self, start: str, router: Callable[[State], str]) -> "StateGraph":
        """After ``start`` runs, call ``router(state)`` to pick the next node name
        (or ``END``)."""
        self._conditional_edges[start] = router
        return self

    def compile(self, max_steps: int = 1000) -> "CompiledGraph":
        if self._entry_point is None:
            raise GraphError("no entry point set - call set_entry_point() first")
        if self._entry_point not in self._nodes:
            raise GraphError(f"entry point '{self._entry_point}' is not a registered node")
        for start, end in self._edges.items():
            if end != END and end not in self._nodes:
                raise GraphError(f"edge {start!r} -> {end!r} references an unknown node")
        return CompiledGraph(
            nodes=dict(self._nodes),
            edges=dict(self._edges),
            conditional_edges=dict(self._conditional_edges),
            entry_point=self._entry_point,
            max_steps=max_steps,
        )


@dataclass
class CompiledGraph:
    nodes: Dict[str, NodeFn]
    edges: Dict[str, str]
    conditional_edges: Dict[str, Callable[[State], str]]
    entry_point: str
    max_steps: int = 1000

    def invoke(self, initial_state: MutableMapping[str, Any]) -> State:
        """Runs nodes starting at the entry point until a node routes to ``END`` or
        ``max_steps`` node executions have happened (a safety valve against an
        accidentally-cyclic graph, not a normal exit path)."""
        state: State = dict(initial_state)
        current = self.entry_point
        executed = 0
        while current != END:
            if executed >= self.max_steps:
                raise GraphError(
                    f"graph exceeded max_steps={self.max_steps} without reaching END - "
                    "likely an edge that never routes to END"
                )
            if current not in self.nodes:
                raise GraphError(f"no such node: {current!r}")
            state = self.nodes[current](state)
            executed += 1

            if current in self.conditional_edges:
                current = self.conditional_edges[current](state)
            elif current in self.edges:
                current = self.edges[current]
            else:
                # A node with no outgoing edge implicitly ends the graph, matching
                # LangGraph's behaviour when a node has no declared next step.
                current = END
        return state
