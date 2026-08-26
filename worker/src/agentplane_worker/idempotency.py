"""Checkpoint-file idempotency for the worker's own step loop.

This is the *worker-side* half of the idempotency story; the control-plane
side is ``stream.IdempotencyGuard`` (a Redis ``SETNX``) which stops a
redelivered Redis Streams message from launching a second Kubernetes Job for
the same run at all (see RUNBOOK §8 and ``docs/adr/0003-redis-streams-over-queue.md``).
This module covers what happens *after* a Job pod does start: if a pod is
killed and Kubernetes (or an operator) creates a replacement pod for the same
run before the control plane's guard would have caught it -- or simply for a
manual re-run with the same idempotency key -- the new pod should not redo
tool calls the previous attempt already completed and checkpointed.

The checkpoint is a single JSON file per idempotency key, written atomically
(write to a temp file in the same directory, then ``os.replace``, which is
atomic on POSIX and on Windows for files on the same volume) so a crash
mid-write never leaves a half-written, unparseable checkpoint behind.
"""

from __future__ import annotations

import json
import os
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional


@dataclass
class Checkpoint:
    idempotency_key: str
    completed_steps: dict[int, Any] = field(default_factory=dict)
    status: str = "IN_PROGRESS"  # IN_PROGRESS | SUCCEEDED | FAILED
    result: Optional[dict] = None
    updated_at: float = field(default_factory=time.time)

    def to_json(self) -> dict:
        return {
            "idempotencyKey": self.idempotency_key,
            # JSON object keys must be strings; step indices are restored as ints on load.
            "completedSteps": {str(k): v for k, v in self.completed_steps.items()},
            "status": self.status,
            "result": self.result,
            "updatedAt": self.updated_at,
        }

    @staticmethod
    def from_json(data: dict) -> "Checkpoint":
        return Checkpoint(
            idempotency_key=data["idempotencyKey"],
            completed_steps={int(k): v for k, v in data.get("completedSteps", {}).items()},
            status=data.get("status", "IN_PROGRESS"),
            result=data.get("result"),
            updated_at=data.get("updatedAt", time.time()),
        )


class IdempotencyStore:
    """Reads/writes one checkpoint file per idempotency key under ``directory``."""

    def __init__(self, directory: str | os.PathLike):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)

    def _path(self, idempotency_key: str) -> Path:
        # Idempotency keys come from a UUID run id today, but sanitise defensively
        # rather than trust that forever - this file name must never escape the
        # checkpoint directory.
        safe_key = "".join(c if c.isalnum() or c in "-_." else "_" for c in idempotency_key)
        return self.directory / f"{safe_key}.json"

    def load(self, idempotency_key: str) -> Optional[Checkpoint]:
        path = self._path(idempotency_key)
        if not path.exists():
            return None
        try:
            with path.open("r", encoding="utf-8") as fh:
                return Checkpoint.from_json(json.load(fh))
        except (json.JSONDecodeError, KeyError, OSError):
            # A corrupt/partial checkpoint is treated as "no checkpoint" rather than
            # a fatal error - the worker just redoes the run from the start, which is
            # always safe (if slower), unlike trusting corrupt data would be.
            return None

    def save(self, checkpoint: Checkpoint) -> None:
        checkpoint.updated_at = time.time()
        path = self._path(checkpoint.idempotency_key)
        fd, tmp_path = tempfile.mkstemp(dir=str(self.directory), prefix=".tmp-", suffix=".json")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(checkpoint.to_json(), fh)
            os.replace(tmp_path, path)  # atomic on the same filesystem
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

    def is_step_complete(self, idempotency_key: str, step_index: int) -> bool:
        checkpoint = self.load(idempotency_key)
        return checkpoint is not None and step_index in checkpoint.completed_steps

    def is_finished(self, idempotency_key: str) -> bool:
        checkpoint = self.load(idempotency_key)
        return checkpoint is not None and checkpoint.status in ("SUCCEEDED", "FAILED")

    def record_step(self, idempotency_key: str, step_index: int, result: Any) -> Checkpoint:
        checkpoint = self.load(idempotency_key) or Checkpoint(idempotency_key=idempotency_key)
        checkpoint.completed_steps[step_index] = result
        self.save(checkpoint)
        return checkpoint

    def record_finished(self, idempotency_key: str, status: str, result: Optional[dict]) -> Checkpoint:
        checkpoint = self.load(idempotency_key) or Checkpoint(idempotency_key=idempotency_key)
        checkpoint.status = status
        checkpoint.result = result
        self.save(checkpoint)
        return checkpoint
