# AGENTPLANE worker

The Kubernetes `Job` payload that `orchestration.Fabric8JobLauncher` (control plane) creates
one of per run. Python 3.11, no LLM framework dependency, LangGraph-shaped state machine
(see `src/agentplane_worker/graph.py` for why it's a lookalike rather than the real
`langgraph` package).

## What it does

1. Reads a run spec from the environment (`AGENTPLANE_RUN_ID`, `AGENTPLANE_PROMPT`,
   `AGENTPLANE_MODEL`, `AGENTPLANE_MAX_STEPS` - the same env vars `Fabric8JobLauncher` injects
   into the pod it creates), with CLI-flag overrides for local runs.
2. Walks a small graph (`search_docs -> fetch_metric -> summarize`, capped by `max_steps`)
   emitting one structured JSON trace event per line on stdout for every step/tool call -
   shaped like the control plane's `trace.RunTrace` document (`type`, `toolName`, `latencyMs`,
   `status`, `payload`, `error`), though this worker never writes to MongoDB itself.
3. Checkpoints each completed step to a local JSON file keyed by an idempotency key, so a
   replacement pod for the same run (redelivery, manual retry) skips steps a previous attempt
   already finished, and short-circuits entirely if the previous attempt already reached a
   terminal status.
4. Reports its final status back to the control plane over mTLS - best-effort; see below.

## Running it locally

```bash
cd worker
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

AGENTPLANE_RUN_ID=demo-run-1 \
AGENTPLANE_PROMPT="investigate the tool latency regression" \
AGENTPLANE_MODEL=demo-model \
AGENTPLANE_MAX_STEPS=10 \
python -m agentplane_worker
```

No control plane, no mTLS material, and no Kubernetes cluster are required for this - if
`AGENTPLANE_CONTROL_PLANE_URL` (and the cert/key/CA env vars below) are unset, the worker logs
that it is skipping the status callback and exits based on its own run outcome. This mirrors
the control plane's own `NoopJobLauncher` philosophy: the thing that matters most (the state
machine, the tracing, the idempotency) is fully exercisable with nothing else running.

Checkpoints are written under `CHECKPOINT_DIR` (default `/var/run/agentplane/checkpoints`,
override to e.g. `./.checkpoints` for a local run).

## Tests

```bash
python -m pytest
```

40 tests, all hermetic (no network, no real cluster). The mTLS tests
(`tests/test_control_plane_client.py`) are a genuine exception to "no network": they generate
short-lived certificates with the system `openssl` binary (see `tests/conftest.py`) and run a
real local HTTPS server on `127.0.0.1` requiring a client certificate, so the success path, the
"worker doesn't trust the control plane's CA" path, and the "control plane doesn't trust this
worker's client cert" path are all exercised against real TLS handshakes, not mocks.

## Configuration

| Env var | Required | Description |
|---|---|---|
| `AGENTPLANE_RUN_ID` | yes | Run identifier; also used as the trace `runId` and the idempotency-key fallback |
| `AGENTPLANE_PROMPT` | yes | The run's prompt, threaded into the `search_docs` tool call |
| `AGENTPLANE_MODEL` | yes | Model name (not actually called - see `agent.py` docstring) |
| `AGENTPLANE_MAX_STEPS` | yes | Upper bound on tool-plan steps executed |
| `IDEMPOTENCY_KEY` | no | Explicit idempotency key; falls back to `AGENTPLANE_RUN_ID` if unset (see `run_spec.py`) |
| `CHECKPOINT_DIR` | no | Where step checkpoints are written (default `/var/run/agentplane/checkpoints`) |
| `AGENTPLANE_CONTROL_PLANE_URL` | no | Base URL for the status callback; omit to skip reporting entirely |
| `AGENTPLANE_CLIENT_CERT` / `AGENTPLANE_CLIENT_KEY` | no* | This worker's mTLS client certificate/key |
| `AGENTPLANE_CA_BUNDLE` | no* | CA bundle used to verify the control plane's server certificate |
| `LOG_LEVEL` | no | Python logging level (default `INFO`) |

\* Required together if `AGENTPLANE_CONTROL_PLANE_URL` is set - see `control_plane_client.py`.

## mTLS: what's real and what's a documented gap

The mTLS client (`control_plane_client.py`) is a complete, independently-tested implementation:
it presents a client certificate, verifies the control plane's server certificate against a CA
bundle, refuses to fall back to plaintext or unverified TLS under any circumstance, and
distinguishes a TLS trust failure from an ordinary connection/timeout failure in its logging
(see the module docstring for the keystore/truststore framing, cross-referenced to
`docs/RUNBOOK.md` §4).

What is **not** real: the control plane does not currently expose the endpoint this client
posts to (`POST /api/v1/internal/runs/{id}/status`) - `control-plane/src` was out of scope for
this change. In today's codebase, a real cluster's run status is observed the other way around:
`orchestration.PodStatusWatcher` polls pod phase via the Kubernetes API. This client is the
worker-side half of a call-home contract the control plane would need a matching endpoint for;
it is built and tested here against *any* correctly-shaped HTTPS endpoint, which is what could
actually be verified without modifying the control plane. See `docs/ARCHITECTURE.md`, "known
limitations".

## Layout

```
src/agentplane_worker/
  run_spec.py             RunSpec - env/CLI parsing, validation
  graph.py                Dependency-free StateGraph/CompiledGraph (LangGraph-shaped API)
  tools.py                Three deterministic fake tool calls + the fixed tool plan
  trace.py                TraceEmitter (structured JSON lines) + ToolTimer context manager
  idempotency.py          Checkpoint + IdempotencyStore (atomic JSON file per idempotency key)
  agent.py                Wires the above into build_graph()/execute_run()
  control_plane_client.py mTLS HTTP client (MtlsConfig, ControlPlaneClient)
  __main__.py             CLI entrypoint, exit code = run outcome
tests/                    pytest - state machine, idempotency, trace shape, run-spec parsing,
                          and real mTLS handshakes (success + both failure directions)
```
