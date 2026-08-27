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

# Strip the packaging tools out of the venv once the app is installed.
#
# pip / setuptools / wheel (and setuptools' vendored jaraco.context) are build-time only — the
# worker imports nothing from them at runtime, it needs the app package and `requests`. They
# were also the entire Python-package finding set in the image scan: wheel (CVE-2026-24049,
# arbitrary code execution via a malicious wheel) and jaraco.context (CVE-2026-23949, tar path
# traversal), among others. Removing them clears those findings and shrinks the attack surface
# to exactly what runs. Verified: the worker still imports and runs with all three gone.
RUN pip uninstall -y wheel setuptools pip \
    && rm -rf /opt/venv/lib/python*/site-packages/jaraco* \
              /opt/venv/lib/python*/site-packages/pkg_resources \
              /opt/venv/lib/python*/site-packages/_distutils_hack \
              /opt/venv/lib/python*/site-packages/setuptools* \
              /opt/venv/lib/python*/site-packages/wheel*

# --- Runtime stage -------------------------------------------------------------------------
FROM python:3.11-slim

# Patch the base image's OS packages before anything else. The python:3.11-slim tag is rebuilt
# on its own cadence and can lag the Debian security repo, which is how a fixable OS-package CVE
# reaches the image scan even though a fix is published. Upgrading here ships the patched
# packages at build time. (The control-plane image does the equivalent apt-get upgrade.)
RUN apt-get update \
    && apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/*

# Remove the SYSTEM Python's packaging tools too. python:3.11-slim ships pip/setuptools/wheel in
# /usr/local — and setuptools bundles vendored copies of wheel 0.45.1 (CVE-2026-24049) and
# jaraco.context 5.3.0 (CVE-2026-23949) under its _vendor/ directory, which the image scan reads
# from their dist-info METADATA. The app never runs from the system interpreter — it runs from
# the /opt/venv copied below, and that venv is built with include-system-site-packages=false, so
# it cannot see these anyway. Removing them clears the findings without affecting the app.
RUN rm -rf /usr/local/lib/python3.11/site-packages/setuptools* \
           /usr/local/lib/python3.11/site-packages/pkg_resources \
           /usr/local/lib/python3.11/site-packages/_distutils_hack \
           /usr/local/lib/python3.11/site-packages/wheel* \
           /usr/local/lib/python3.11/site-packages/pip* \
           /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.11 \
    && find /usr/local/lib/python3.11/site-packages -maxdepth 1 -name 'jaraco*' -exec rm -rf {} +

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
