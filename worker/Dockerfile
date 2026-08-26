# --- Build stage --------------------------------------------------------------------------
# Builds a self-contained virtualenv so the runtime stage never needs a C toolchain or pip.
FROM python:3.11-slim AS build
WORKDIR /build

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY pyproject.toml requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY src src
RUN pip install --no-cache-dir --no-deps .

# --- Runtime stage -------------------------------------------------------------------------
FROM python:3.11-slim

# Run as a non-root user - this is a Kubernetes Job payload, never a reason to run as root,
# and the mTLS client material it reads (AGENTPLANE_CLIENT_CERT/_KEY) should not be readable
# by anything other than this user either (mount them 0440, owned by this uid, in the chart).
RUN groupadd --system agentplane && useradd --system --gid agentplane --home /home/agentplane --create-home agentplane

COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    CHECKPOINT_DIR=/var/run/agentplane/checkpoints

# Idempotency checkpoints (idempotency.py) live here - a Job spec should mount this as an
# emptyDir (or, for a real checkpoint-across-pod-restarts story, a small PVC) rather than
# leave it as ephemeral container-local storage, since the whole point is surviving the
# container being replaced. See charts/agentplane values for the worker Job template notes.
RUN mkdir -p /var/run/agentplane/checkpoints && chown -R agentplane:agentplane /var/run/agentplane
USER agentplane
WORKDIR /home/agentplane

# No HEALTHCHECK: this image is a Kubernetes Job payload (run to completion, then exit),
# not a long-lived server - Kubernetes observes success/failure via the container's exit
# code (see orchestration.PodStatusWatcher on the control-plane side), not a liveness probe.
ENTRYPOINT ["python", "-m", "agentplane_worker"]
