"""The run spec this worker executes, and where it comes from.

``orchestration.Fabric8JobLauncher`` (control-plane) injects ``AGENTPLANE_RUN_ID``,
``AGENTPLANE_PROMPT``, ``AGENTPLANE_MODEL`` and ``AGENTPLANE_MAX_STEPS`` as
container env vars on the Job it creates, plus every entry of the run's own
``env`` map verbatim (see that class). Everything below reads from the
environment by default, with CLI overrides for local runs (``python -m
agentplane_worker --run-id ... --prompt ...``) so this is exercisable without a
control plane at all -- the same "runs with nothing" spirit as the control
plane's own ``NoopJobLauncher``.

One deliberate gap, called out here rather than hidden: the control plane does
not currently mint an ``IDEMPOTENCY_KEY`` env var for the Job it creates (see
``Fabric8JobLauncher.launchJob``). Until it does, this worker falls back to
``AGENTPLANE_RUN_ID`` as its idempotency key -- stable across a redelivered
message for the *same* run, which is the case that matters (see
``docs/ARCHITECTURE.md`` and RUNBOOK §8 for the control-plane-side half of this
story).
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass


class RunSpecError(ValueError):
    """Raised when a required piece of the run spec is missing from both the
    environment and the CLI arguments."""


@dataclass(frozen=True)
class RunSpec:
    run_id: str
    prompt: str
    model: str
    max_steps: int
    idempotency_key: str

    @staticmethod
    def from_env_and_args(argv: list[str] | None = None) -> "RunSpec":
        parser = argparse.ArgumentParser(
            prog="agentplane-worker",
            description="AGENTPLANE agent worker - executes one run and exits.",
        )
        parser.add_argument("--run-id", dest="run_id", default=None)
        parser.add_argument("--prompt", dest="prompt", default=None)
        parser.add_argument("--model", dest="model", default=None)
        parser.add_argument("--max-steps", dest="max_steps", type=int, default=None)
        parser.add_argument("--idempotency-key", dest="idempotency_key", default=None)
        args = parser.parse_args(argv)

        run_id = args.run_id or os.environ.get("AGENTPLANE_RUN_ID")
        prompt = args.prompt or os.environ.get("AGENTPLANE_PROMPT")
        model = args.model or os.environ.get("AGENTPLANE_MODEL")
        max_steps_raw = args.max_steps or os.environ.get("AGENTPLANE_MAX_STEPS")
        idempotency_key = (
            args.idempotency_key
            or os.environ.get("IDEMPOTENCY_KEY")
            or run_id  # fallback described in the module docstring above
        )

        missing = [
            name
            for name, value in (
                ("run-id / AGENTPLANE_RUN_ID", run_id),
                ("prompt / AGENTPLANE_PROMPT", prompt),
                ("model / AGENTPLANE_MODEL", model),
                ("max-steps / AGENTPLANE_MAX_STEPS", max_steps_raw),
            )
            if not value
        ]
        if missing:
            raise RunSpecError(
                "missing required run spec field(s): " + ", ".join(missing)
            )

        try:
            max_steps = int(max_steps_raw)
        except (TypeError, ValueError) as exc:
            raise RunSpecError(f"max-steps must be an integer, got {max_steps_raw!r}") from exc
        if max_steps < 1:
            raise RunSpecError(f"max-steps must be >= 1, got {max_steps}")

        return RunSpec(
            run_id=run_id,
            prompt=prompt,
            model=model,
            max_steps=max_steps,
            idempotency_key=idempotency_key,
        )
