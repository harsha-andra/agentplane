"""Structured JSON trace events, one per line on stdout.

This intentionally mirrors the shape of the control plane's ``trace.RunTrace``
Mongo document (``type`` / ``toolName`` / ``startedAt`` / ``latencyMs`` /
``status`` / ``payload`` / ``error``) without depending on the control plane in
any way: this worker never writes to MongoDB directly. In a real deployment a
node-level log shipper (e.g. an agent reading the container's stdout) is what
would turn these JSON lines into ``RunTrace`` documents -- that pipeline is not
built here (see "known limitations" in ``docs/ARCHITECTURE.md``), but shaping
the emitted events the same way makes that a mechanical follow-up rather than a
redesign.

Emitting to stdout rather than calling out over the network keeps trace
emission fire-and-forget from the worker's perspective: a slow or unreachable
collector never blocks or fails the agent's own step loop, only the final
status report (``control_plane_client.py``) does that, deliberately.
"""

from __future__ import annotations

import json
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class TraceEmitter:
    run_id: str
    _seq: int = field(default=0, init=False)
    _stream: Any = field(default=sys.stdout, init=False)

    def _emit(self, event: dict) -> None:
        event.setdefault("runId", self.run_id)
        event.setdefault("seq", self._next_seq())
        event.setdefault("startedAt", _now_iso())
        print(json.dumps(event, default=str), file=self._stream, flush=True)

    def _next_seq(self) -> int:
        self._seq += 1
        return self._seq

    def log(self, level: str, message: str, **payload: Any) -> None:
        self._emit({
            "type": "LOG",
            "status": "SUCCESS",
            "level": level,
            "message": message,
            "payload": payload or None,
        })

    def step(self, step_index: int, status: str, **payload: Any) -> None:
        self._emit({
            "type": "STEP",
            "status": status,
            "step": step_index,
            "payload": payload or None,
        })

    def tool_call(
        self,
        tool_name: str,
        status: str,
        latency_ms: Optional[int] = None,
        error: Optional[str] = None,
        **payload: Any,
    ) -> None:
        self._emit({
            "type": "TOOL_CALL",
            "toolName": tool_name,
            "status": status,
            "latencyMs": latency_ms,
            "error": error,
            "payload": payload or None,
        })


class ToolTimer:
    """Context manager that times a tool call and emits a TOOL_CALL trace event
    on exit, success or failure, so callers cannot forget the failure path."""

    def __init__(self, emitter: TraceEmitter, tool_name: str, **payload: Any):
        self._emitter = emitter
        self._tool_name = tool_name
        self._payload = payload
        self._start = 0.0

    def __enter__(self) -> "ToolTimer":
        self._start = time.monotonic()
        return self

    def __exit__(self, exc_type, exc, _tb) -> bool:
        latency_ms = int((time.monotonic() - self._start) * 1000)
        if exc is not None:
            self._emitter.tool_call(
                self._tool_name, "ERROR", latency_ms=latency_ms, error=str(exc), **self._payload
            )
        else:
            self._emitter.tool_call(
                self._tool_name, "SUCCESS", latency_ms=latency_ms, **self._payload
            )
        return False  # never swallow the exception
