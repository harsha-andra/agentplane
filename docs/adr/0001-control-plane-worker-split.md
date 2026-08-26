# 0001 - Control plane / worker split

**Date:** 2026-08-26
**Status:** Accepted

## Context

AGENTPLANE runs LLM agent workloads. Two things needed to happen: (1) accept a run request,
persist it, queue it, decide when/where it runs, track its lifecycle, and expose that state
(REST + SSE + analytics); and (2) actually execute an agent - read a prompt, call tools, produce
a result. These have different concurrency models (a long-running service handling many
concurrent HTTP/SSE connections vs. a short-lived, single-purpose process), different failure
domains (a control-plane bug should not be able to corrupt a run's own execution and vice versa),
and different natural implementation languages (the control plane's value is in Spring's
ecosystem for persistence/streaming/observability; agent execution's value is in Python's
LLM/tool-calling ecosystem).

## Decision

Split into two independently deployable programs:

- **`control-plane/`** (Java 21, Spring Boot 3.3): owns the API, Postgres, MongoDB, Redis
  Streams, SSE, and orchestration decisions. Never executes agent logic itself.
- **`worker/`** (Python 3.11): the payload of a Kubernetes `Job` the control plane launches, one
  per run. Owns nothing about scheduling/queuing/multi-tenancy - it reads a run spec, executes,
  reports back, and exits.

The two communicate through exactly two channels: the control plane launches the worker as a
`Job` with its run spec as env vars (via `orchestration.JobLauncher` - see ADR 0005), and the
worker reports its final status back over mTLS (see ADR 0004). No shared database, no shared
process, no shared deploy.

## Consequences

- The control plane can be developed, tested, and deployed independently of anything about how
  agents are actually implemented - swapping the worker's internals (a different agent
  framework, a different language even) never touches `control-plane/src`.
- Kubernetes' own primitives (a `Job`'s `backoffLimit`, `activeDeadlineSeconds`,
  `ResourceQuota`/`LimitRange` per tenant namespace) become the actual isolation/resource
  boundary between one run and another, rather than something the control plane process has to
  enforce itself (e.g. a thread pool per tenant).
- The trade-off: this is two runtimes, two test suites, two sets of dependencies to keep patched,
  and the interface between them (env vars in, an HTTP status report out) is an informal contract
  rather than something a compiler checks across the language boundary. See
  `docs/ARCHITECTURE.md`'s "known limitations" for where that contract is currently incomplete
  (the worker's status-report endpoint doesn't exist on the control plane yet).
