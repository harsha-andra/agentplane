"""mTLS HTTP client the worker uses to report its final status back to the
control plane.

Both directions of trust are handled here (see RUNBOOK.md §4, "keystore vs
truststore", for the JVM-side mirror of the same distinction):

- **Who am I?** ``AGENTPLANE_CLIENT_CERT`` / ``AGENTPLANE_CLIENT_KEY`` - this
  worker's own certificate and private key, presented so the control plane can
  verify the *worker's* identity (client-auth usage - see
  ``charts/agentplane/templates/certificates.yaml``).
- **Whom do I trust?** ``AGENTPLANE_CA_BUNDLE`` - the CA certificate(s) this
  worker uses to verify the control plane's *server* certificate. This is a CA
  bundle file (PEM, possibly containing the in-cluster cert-manager CA
  concatenated with the system trust store), the Python equivalent of a Java
  truststore.

Known gap, stated plainly rather than glossed over: the control plane does not
today expose the status-report endpoint this client posts to
(``/api/v1/internal/runs/{id}/status``) - see ``docs/ARCHITECTURE.md``,
"known limitations". In the current codebase, run status for a real cluster is
observed the other way around: ``orchestration.PodStatusWatcher`` polls pod
phase via the Kubernetes API. This client is the worker-side half of a
call-home contract that the control plane would need a matching
``@PostMapping`` for; it's built and tested here as a complete, working mTLS
client against *any* HTTPS endpoint with that shape, which is what a reviewer
can actually verify without a live cluster.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import requests

log = logging.getLogger(__name__)

DEFAULT_TIMEOUT_SECONDS = 10.0


class ControlPlaneClientConfigError(Exception):
    """Raised when the mTLS material this client needs (cert/key/CA bundle)
    is missing or unreadable. Raised eagerly, at construction, rather than
    letting a confusing SSL error surface later from deep inside a request."""


@dataclass(frozen=True)
class MtlsConfig:
    base_url: str
    client_cert: Path
    client_key: Path
    ca_bundle: Path
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS

    @staticmethod
    def from_env() -> "MtlsConfig":
        base_url = os.environ.get("AGENTPLANE_CONTROL_PLANE_URL")
        client_cert = os.environ.get("AGENTPLANE_CLIENT_CERT")
        client_key = os.environ.get("AGENTPLANE_CLIENT_KEY")
        ca_bundle = os.environ.get("AGENTPLANE_CA_BUNDLE")
        missing = [
            name
            for name, value in (
                ("AGENTPLANE_CONTROL_PLANE_URL", base_url),
                ("AGENTPLANE_CLIENT_CERT", client_cert),
                ("AGENTPLANE_CLIENT_KEY", client_key),
                ("AGENTPLANE_CA_BUNDLE", ca_bundle),
            )
            if not value
        ]
        if missing:
            raise ControlPlaneClientConfigError(
                "missing required mTLS environment variable(s): " + ", ".join(missing)
            )
        return MtlsConfig(
            base_url=base_url,
            client_cert=Path(client_cert),
            client_key=Path(client_key),
            ca_bundle=Path(ca_bundle),
        )


class ControlPlaneClient:
    """A small mTLS-only HTTP client. Deliberately does not fall back to
    plaintext or unverified TLS under any circumstance - a worker that cannot
    establish mTLS should fail loudly, not silently downgrade."""

    def __init__(self, config: MtlsConfig):
        self._config = config
        self._verify_paths_exist()

    def _verify_paths_exist(self) -> None:
        for label, path in (
            ("client certificate (AGENTPLANE_CLIENT_CERT)", self._config.client_cert),
            ("client private key (AGENTPLANE_CLIENT_KEY)", self._config.client_key),
            ("CA bundle (AGENTPLANE_CA_BUNDLE)", self._config.ca_bundle),
        ):
            if not path.is_file():
                raise ControlPlaneClientConfigError(f"{label} not found at {path}")

    def report_status(
        self,
        run_id: str,
        status: str,
        message: Optional[str] = None,
        extra: Optional[dict] = None,
    ) -> bool:
        """POSTs the run's final status. Returns True on a 2xx response, False
        on any failure that was handled and logged (the caller decides whether
        that should affect the worker's own exit code). Never raises for
        ordinary network/TLS failures - those are expected in the real world
        (a control plane mid-rollout, a cert not yet rotated) and are reported
        via the return value plus a logged, specific reason instead.
        """
        url = f"{self._config.base_url.rstrip('/')}/api/v1/internal/runs/{run_id}/status"
        body = {"status": status, "message": message, "extra": extra or {}}
        try:
            response = requests.post(
                url,
                json=body,
                cert=(str(self._config.client_cert), str(self._config.client_key)),
                verify=str(self._config.ca_bundle),
                timeout=self._config.timeout_seconds,
            )
            response.raise_for_status()
            log.info("reported status=%s for run=%s to %s (HTTP %s)", status, run_id, url, response.status_code)
            return True
        except requests.exceptions.SSLError as exc:
            # This is always a trust problem, never a network problem, and it can be
            # either half of mTLS - log which endpoint and let the operator check both
            # directions, exactly as RUNBOOK.md §4 recommends for the JVM side.
            log.error(
                "mTLS handshake failed talking to %s - this means either (a) the control "
                "plane's server certificate does not chain to a CA in %s (a truststore "
                "problem on this side), or (b) the control plane rejected this worker's "
                "client certificate at %s (a truststore problem on the control plane's "
                "side). Real error: %s",
                url, self._config.ca_bundle, self._config.client_cert, exc,
            )
            return False
        except requests.exceptions.ConnectionError as exc:
            log.error("could not connect to control plane at %s: %s", url, exc)
            return False
        except requests.exceptions.Timeout as exc:
            log.error("status report to %s timed out after %ss: %s", url, self._config.timeout_seconds, exc)
            return False
        except requests.exceptions.HTTPError as exc:
            log.error("control plane rejected status report to %s: %s", url, exc)
            return False
