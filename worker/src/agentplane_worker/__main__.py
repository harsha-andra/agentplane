"""Entrypoint: ``python -m agentplane_worker`` (also the Dockerfile's ENTRYPOINT).

Exit code mirrors the run's own outcome (0 = SUCCEEDED, 1 = FAILED), regardless
of whether the status-report call-home to the control plane itself succeeded -
see ``control_plane_client.py`` for why that call is optional/best-effort
rather than fatal.
"""

from __future__ import annotations

import logging
import os
import sys

from .agent import execute_run
from .control_plane_client import ControlPlaneClient, ControlPlaneClientConfigError, MtlsConfig
from .idempotency import IdempotencyStore
from .run_spec import RunSpec, RunSpecError
from .trace import TraceEmitter

DEFAULT_CHECKPOINT_DIR = "/var/run/agentplane/checkpoints"


def _configure_logging() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def main(argv: list[str] | None = None) -> int:
    _configure_logging()
    log = logging.getLogger("agentplane_worker")

    try:
        spec = RunSpec.from_env_and_args(argv)
    except RunSpecError as exc:
        log.error("invalid run spec: %s", exc)
        return 2

    checkpoint_dir = os.environ.get("CHECKPOINT_DIR", DEFAULT_CHECKPOINT_DIR)
    idempotency_store = IdempotencyStore(checkpoint_dir)
    trace_emitter = TraceEmitter(run_id=spec.run_id)

    log.info(
        "starting run_id=%s model=%s max_steps=%s idempotency_key=%s checkpoint_dir=%s",
        spec.run_id, spec.model, spec.max_steps, spec.idempotency_key, checkpoint_dir,
    )

    final_state = execute_run(spec, idempotency_store, trace_emitter)
    status = final_state["status"]
    log.info("run_id=%s finished status=%s resumed=%s", spec.run_id, status, final_state.get("resumed"))

    _report_status(spec.run_id, status, final_state.get("error"), log)

    return 0 if status == "SUCCEEDED" else 1


def _report_status(run_id: str, status: str, error: str | None, log: logging.Logger) -> None:
    """Best-effort call-home. Absence of the mTLS env vars is treated as
    "control plane reporting not configured for this run" (e.g. local/dev runs
    via ``docker run`` with no control plane at all) rather than an error -
    the worker still did its job and exits based on its own run outcome."""
    try:
        mtls_config = MtlsConfig.from_env()
    except ControlPlaneClientConfigError as exc:
        log.info("skipping control-plane status report: %s", exc)
        return

    try:
        client = ControlPlaneClient(mtls_config)
    except ControlPlaneClientConfigError as exc:
        log.error("cannot report status - mTLS material invalid: %s", exc)
        return

    reported = client.report_status(run_id, status, message=error)
    if not reported:
        log.warning(
            "run_id=%s completed with status=%s but the control plane could not be notified "
            "(see the error above) - it will observe this run's outcome via its own "
            "pod-status watcher instead, once one is reachable",
            run_id, status,
        )


if __name__ == "__main__":
    sys.exit(main())
