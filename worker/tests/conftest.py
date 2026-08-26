"""Shared fixtures.

The mTLS tests spin up a real local HTTPS server (stdlib ``http.server`` +
``ssl``, wrapping the *listening* socket so every accepted connection performs
a real TLS/mTLS handshake) and generate real short-lived certificates with the
system ``openssl`` binary - this is a genuine mTLS round trip, not a mock of
the ``ssl``/``requests`` modules. Kept in ``conftest.py`` so both
``test_control_plane_client.py`` and any future test file can reuse the same
tiny CA.
"""

from __future__ import annotations

import http.server
import ssl
import subprocess
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import pytest


def _run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True, capture_output=True)


def _make_ca(work_dir: Path, name: str) -> tuple[Path, Path]:
    key = work_dir / f"{name}.key"
    crt = work_dir / f"{name}.crt"
    _run([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-keyout", str(key), "-out", str(crt), "-days", "2",
        "-subj", f"/CN={name}",
    ])
    return crt, key


def _issue_cert(
    work_dir: Path, name: str, ca_crt: Path, ca_key: Path, *, ext_key_usage: str, san: str | None = None,
) -> tuple[Path, Path]:
    key = work_dir / f"{name}.key"
    csr = work_dir / f"{name}.csr"
    crt = work_dir / f"{name}.crt"
    extfile = work_dir / f"{name}.extfile.cnf"

    _run([
        "openssl", "req", "-newkey", "rsa:2048", "-nodes",
        "-keyout", str(key), "-out", str(csr), "-subj", f"/CN={name}",
    ])

    ext_lines = [f"extendedKeyUsage={ext_key_usage}"]
    if san:
        ext_lines.append(f"subjectAltName={san}")
    extfile.write_text("\n".join(ext_lines) + "\n")

    _run([
        "openssl", "x509", "-req", "-in", str(csr),
        "-CA", str(ca_crt), "-CAkey", str(ca_key), "-CAcreateserial",
        "-out", str(crt), "-days", "2", "-extfile", str(extfile),
    ])
    return crt, key


@dataclass(frozen=True)
class TestPki:
    ca_crt: Path
    rogue_ca_crt: Path
    server_crt: Path
    server_key: Path
    client_crt: Path
    client_key: Path
    rogue_client_crt: Path
    rogue_client_key: Path


@pytest.fixture(scope="session")
def pki(tmp_path_factory: pytest.TempPathFactory) -> TestPki:
    work_dir = tmp_path_factory.mktemp("agentplane-pki")

    ca_crt, ca_key = _make_ca(work_dir, "agentplane-test-ca")
    rogue_ca_crt, rogue_ca_key = _make_ca(work_dir, "rogue-ca")

    server_crt, server_key = _issue_cert(
        work_dir, "control-plane-test", ca_crt, ca_key,
        ext_key_usage="serverAuth", san="DNS:localhost,IP:127.0.0.1",
    )
    client_crt, client_key = _issue_cert(
        work_dir, "worker-test", ca_crt, ca_key, ext_key_usage="clientAuth",
    )
    rogue_client_crt, rogue_client_key = _issue_cert(
        work_dir, "rogue-worker", rogue_ca_crt, rogue_ca_key, ext_key_usage="clientAuth",
    )

    return TestPki(
        ca_crt=ca_crt,
        rogue_ca_crt=rogue_ca_crt,
        server_crt=server_crt,
        server_key=server_key,
        client_crt=client_crt,
        client_key=client_key,
        rogue_client_crt=rogue_client_crt,
        rogue_client_key=rogue_client_key,
    )


class _RecordingHandler(http.server.BaseHTTPRequestHandler):
    received: list[tuple[str, bytes]] = []

    def log_message(self, *args, **kwargs) -> None:  # silence default stderr logging
        pass

    def do_POST(self) -> None:  # noqa: N802 (stdlib naming convention)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        _RecordingHandler.received.append((self.path, body))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"acknowledged": true}')


@dataclass(frozen=True)
class MtlsServer:
    base_url: str
    received: list[tuple[str, bytes]]


@pytest.fixture
def mtls_server(pki: TestPki) -> Iterator[MtlsServer]:
    """An HTTPS server on 127.0.0.1 requiring a client certificate signed by
    ``pki.ca_crt``, wrapping the *listening* socket so every accepted
    connection is a full TLS handshake (see module docstring)."""
    _RecordingHandler.received = []

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=str(pki.server_crt), keyfile=str(pki.server_key))
    context.verify_mode = ssl.CERT_REQUIRED
    context.load_verify_locations(cafile=str(pki.ca_crt))

    httpd = http.server.HTTPServer(("127.0.0.1", 0), _RecordingHandler, bind_and_activate=False)
    httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    httpd.server_bind()
    httpd.server_activate()

    port = httpd.server_address[1]
    thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()

    try:
        yield MtlsServer(base_url=f"https://127.0.0.1:{port}", received=_RecordingHandler.received)
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)
