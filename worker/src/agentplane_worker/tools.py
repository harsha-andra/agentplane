"""A handful of deliberately fake tool calls.

Real agent tools would call out to search APIs, code execution sandboxes, other
services, etc. These stand in for that so the state machine and trace/
idempotency plumbing around tool calls are genuinely exercised without this
worker needing real credentials or network access to run - the same
"explorable with nothing" philosophy as the control plane's own
``NoopJobLauncher``. Swapping one of these for a real integration touches only
this file and the corresponding node in ``agent.py``.
"""

from __future__ import annotations

import hashlib
from typing import Any


class ToolError(RuntimeError):
    """Raised by a tool to signal a failed call - distinct from a Python bug in
    the tool itself, so the graph can decide whether to fail the run cleanly."""


def search_docs(query: str) -> dict[str, Any]:
    """Pretends to search a knowledge base. Deterministic given ``query`` so
    tests can assert on the exact output."""
    digest = hashlib.sha256(query.encode("utf-8")).hexdigest()[:8]
    return {
        "query": query,
        "hits": [
            {"title": f"doc-{digest}-1", "score": 0.91},
            {"title": f"doc-{digest}-2", "score": 0.77},
        ],
    }


def fetch_metric(name: str) -> dict[str, Any]:
    """Pretends to read an operational metric."""
    if not name:
        raise ToolError("fetch_metric requires a non-empty metric name")
    value = int(hashlib.sha256(name.encode("utf-8")).hexdigest(), 16) % 1000 / 10.0
    return {"metric": name, "value": value, "unit": "ms"}


def summarize(fragments: list[str]) -> dict[str, Any]:
    """Pretends to summarize a list of text fragments into one string."""
    if not fragments:
        raise ToolError("summarize requires at least one fragment")
    joined = " ".join(fragments)
    return {"summary": joined[:280], "fragmentCount": len(fragments)}


# The plan the state machine walks through, in order. Kept as simple
# (name, callable) pairs rather than a registry/dict-of-dispatch, because the
# order *is* the plan here - there is no dynamic tool selection in this small
# worker (a real LangGraph agent would route through an LLM to pick the next
# tool; this one is a fixed pipeline standing in for that).
DEFAULT_TOOL_PLAN: list[tuple[str, Any]] = [
    ("search_docs", lambda state: search_docs(state["prompt"])),
    ("fetch_metric", lambda state: fetch_metric("tool_latency_ms")),
    ("summarize", lambda state: summarize(
        [hit["title"] for hit in state["tool_results"].get("search_docs", {}).get("hits", [])]
    )),
]
