# AGENTPLANE — Architecture

This is a decisions-and-trade-offs document, not a feature tour. For "what does each endpoint
do", see `control-plane/README.md`; for "how do I fix X", see `docs/RUNBOOK.md` (cross-referenced
throughout below - it is the more heavily battle-tested of the two documents, since a fair
amount of it is captured from things that actually broke while building this).

## 1. The control-plane / worker split, and the `JobLauncher` seam

AGENTPLANE is two programs, not one: a Spring Boot **control plane** that accepts run requests,
persists and queues them, and a Python **worker** (`worker/`) that a Kubernetes `Job` runs to
actually execute an agent. They never share a process, a deployment unit, or even a language.

The interesting design decision isn't the split itself (workload orchestration and workload
execution are naturally different lifecycles - one is a long-running service, the other is
"start, do a bounded amount of work, exit") - it's that the control plane's only doorway to
Kubernetes is one interface, `orchestration.JobLauncher`:

```java
public interface JobLauncher {
    void provisionTenantNamespace(Tenant tenant);
    LaunchedJob launchJob(AgentRun run, Tenant tenant);
    void cancelJob(AgentRun run);
    Optional<PodStatusSnapshot> getPodStatus(AgentRun run);
}
```

`Fabric8JobLauncher` is the real implementation, active only when `agentplane.k8s.enabled=true`.
`NoopJobLauncher` is the default: it simulates a run's lifecycle with scheduled in-process
callbacks (SCHEDULED → RUNNING → SUCCEEDED/FAILED, ~90% simulated success rate, a few fake log
lines) instead of touching Kubernetes at all. Neither the fabric8 `KubernetesClient` bean nor
`Fabric8JobLauncher` are even *constructed* unless the property is set
(`config.KubernetesClientConfig`'s `@ConditionalOnProperty`) - there is no code path where this
application tries, and fails, to reach a cluster that isn't there.

**Why this matters more than it looks like it should:** it is the single decision that makes the
rest of this repository possible to actually run and review. `docker compose up` (or `make up`)
brings up the *entire* system - API, Postgres, MongoDB, Redis Streams, SSE, analytics - with zero
Kubernetes anywhere, because `NoopJobLauncher` stands in for the one piece that would otherwise
require a cluster. The 71 unit tests (`control-plane/README.md`, "Testing") run against this same
seam via mocks, with no Testcontainers, no cluster, no network. A reviewer can clone this repo and
have a working system in the time `docker compose up --build` takes, and can read the
Kubernetes-facing code (`Fabric8JobLauncher`) without ever having to stand up a cluster to trust
that it's exercised - both are true because one interface sits between "orchestrate runs" and "an
actual Kubernetes API server exists". See `docs/adr/0005-joblauncher-abstraction.md`.

## 2. Polyglot persistence: Postgres *and* MongoDB

Two databases, on purpose, holding two genuinely different kinds of data:

- **Postgres** (`domain` package, Flyway-migrated): `Tenant`, `AgentRun`, `AuditEvent`. This is
  the transactional, relationally-queried, low-volume half - a run belongs to a tenant (a real
  foreign key), `AgentRun` needs optimistic locking (`@Version`) because the API handler, the pod
  watcher, and the stream consumer all touch the same row concurrently, and the runs list/
  dashboard queries filter and sort by tenant/status/time range - exactly what a relational engine
  is for. `AuditEvent` is append-only by construction (no setters, every column
  `updatable = false`).
- **MongoDB** (`trace` package): `RunTrace` - one document per log line/tool call/step an agent
  produces. This is the high-volume, schema-varying half: a run with `maxSteps=50` can produce
  dozens of trace documents, each tool's `payload` shape is different and evolves without a
  migration, and nothing here needs a join. Putting this in Postgres would mean either a
  free-for-all JSON column with no query support, or a new migration for every tool a workload
  integrates.

**The aggregation pipeline that actually backs `/api/v1/analytics/tool-latency`**
(`TraceAnalyticsService.toolLatency`) is a real `$match → $group → $project → $sort` pipeline,
not documents pulled into the JVM and folded with a Java stream: it filters `TOOL_CALL` traces in
the trailing window, groups by tool name computing call count and average latency, and sorts by
volume. The one deliberate compromise: MongoDB's native `$percentile` accumulator needs MongoDB
7+, which this system doesn't assume every self-hosted deployment has - so the pipeline instead
`$push`es each group's raw latencies (already reduced to one small array per *tool*, not per
document) and p95 is computed over that small array in application code. A second pipeline,
`errorTagFrequency`, deliberately *does* use `$unwind` - unlike the latency pipeline (where
unwinding would fan a document out per array element and corrupt the average), tag frequency
wants exactly that fan-out, since a trace tagged `["timeout", "retry"]` should count toward both
tags.

**Being honest about `/api/v1/analytics/overview`:** `AnalyticsController.overview()`'s
`runsOverTime` bucketing (`bucketByDay`) is **not** a SQL `GROUP BY date_trunc(...)` - it fetches
every `AgentRun` created in the trailing 7 days via a plain JPA query
(`findByCreatedAtAfter`) and buckets them into a `TreeMap<LocalDate, long[]>` in Java. This is a
legitimate choice at the volume this endpoint is designed for (a dashboard's "last 7 days" view,
bounded window, one query) - it is not a pattern that would scale to bucketing months of runs, and
if this dashboard ever needed that, the fix is a real `GROUP BY` (Postgres has excellent native
date-bucketing) or a materialized rollup table, not the current in-memory grouping. Said plainly:
this is a bounded-window convenience, not evidence that Java-side aggregation is the pattern this
system trusts for the actual analytics endpoints - `TraceAnalyticsService` above is what to look
at for that. See `docs/adr/0002-polyglot-persistence.md`.

## 3. Redis Streams: consumer groups, acknowledgement, and the reclaim problem

`RunStreamProducer` publishes a queued run to a Redis Stream; `RunStreamConsumer` reads it back
through a **consumer group** (`XREADGROUP` semantics, via Spring Data Redis's
`StreamOperations`), not a raw pub/sub subscription - the difference matters because a consumer
group guarantees each message is delivered to exactly one consumer, and remembers *who* is
holding it via a per-group Pending Entries List (PEL), which is the mechanism the rest of this
section depends on.

**Why `XACK` matters:** a message is only removed from the PEL once `RunStreamConsumer.process`
finishes and explicitly acknowledges it. If the worker (control-plane instance) that read a
message dies before that happens, the message is not lost and not automatically redelivered - it
sits in the PEL, permanently, owned by a consumer that no longer exists. See RUNBOOK §8 for a
real captured `XPENDING`/`XINFO GROUPS` session showing exactly this signature (`lag: 0,
pending: 3` - the work was claimed, not merely queued, and then abandoned).

**The reclaim:** `RunStreamConsumer.reclaimStaleEntries` runs on a schedule
(`agentplane.stream.visibility-timeout-ms`, default 60s), finds PEL entries idle longer than that
window, and reclaims them via `XCLAIM` (RUNBOOK §8 also shows the equivalent single-command
`XAUTOCLAIM`) so a live consumer picks the work back up. The visibility timeout is a genuine
trade-off, not a free parameter: too short, and a slow-but-alive worker gets its in-progress work
stolen out from under it; too long, and a customer waits the full timeout after a crash before
anything retries. AGENTPLANE ties reclaim eligibility to the visibility timeout rather than a
single global value tuned to the slowest run class in the system.

**Why reclaim is only safe *because of* the idempotency key:** reclaiming a message means the
same run can be processed twice - once by the worker that "died" (which may not actually be dead,
just slow, or may have completed the side effect and crashed before acking) and once by whoever
reclaims it. `stream.IdempotencyGuard` (a Redis `SETNX` via `setIfAbsent`) is what makes that safe:
`RunLaunchProcessor.processQueuedRun` claims a guard key before doing anything side-effecting, and
a second attempt within the TTL becomes a no-op instead of launching a second Kubernetes `Job` for
the same run. Reclaim without an idempotency guard is exactly how one worker crash turns into
launching a customer's workload twice. See `docs/adr/0003-redis-streams-over-queue.md`.

## 4. SSE over polling

`GET /api/v1/runs/{id}/events` streams status transitions and log lines over Server-Sent Events
rather than the console polling `GET /api/v1/runs/{id}` on an interval. `SseEmitterRegistry` keeps
a `Map<UUID, List<SseEmitter>>` (a run can have more than one live subscriber - two browser tabs,
say), and every emitter is registered with `onCompletion`/`onTimeout`/`onError` callbacks that all
route to the same `remove()` - so a closed tab, a network drop, a client timeout, and a normal
"the run finished" all clean up identically, and the map entry for a run is deleted entirely once
its subscriber list is empty (no per-run entry outlives its last subscriber). `RunEventPublisher`
additionally calls `registry.complete(runId)` the moment a run reaches a terminal status,
proactively closing every emitter for that run rather than waiting for the client to disconnect.
A scheduled heartbeat (`agentplane.sse.heartbeat-interval-ms`, default 15s) sends an SSE comment
line on idle connections so intermediate proxies/load balancers don't kill them for inactivity -
in front of an NGINX ingress specifically, this also needs
`nginx.ingress.kubernetes.io/proxy-buffering: "off"` (see `charts/agentplane/values.yaml`'s
ingress annotations), or the ingress buffers the stream instead of forwarding it as written.

## 5. mTLS between the control plane and workers

The claim: a worker proves its identity to the control plane, and the control plane proves its
identity to the worker, before either side does anything else. See RUNBOOK §4 for the
keystore/truststore framing this is built on (worth reading before this section - the short
version: a *keystore* holds "my certificate and private key, answering who am I"; a *truststore*
holds "other people's CA certificates, answering whom do I believe" - `PKIX path building failed`
is always a truststore problem, never a keystore one).

- **The worker** (`worker/src/agentplane_worker/control_plane_client.py`) presents a client
  certificate (`AGENTPLANE_CLIENT_CERT`/`_KEY` - its keystore) and verifies the control plane's
  server certificate against a CA bundle (`AGENTPLANE_CA_BUNDLE` - its truststore), refusing to
  fall back to plaintext or unverified TLS under any circumstance. This is fully implemented and
  independently tested (`worker/tests/test_control_plane_client.py`) against a real local HTTPS
  server requiring a client certificate - not a mock of `ssl`/`requests` - covering the success
  path and both failure directions (the worker doesn't trust the control plane's CA; the control
  plane doesn't trust the worker's client cert), with certificates generated fresh per test run
  by the system `openssl` binary.
- **The control plane's side of the same handshake is provisioned, but not wired into
  application code** - `charts/agentplane/templates/certificates.yaml` issues a real server
  certificate for the control plane (via cert-manager, `usages: [server auth]`) and mounts it
  into the pod, but `control-plane/src` (out of scope for the Kubernetes/CI layer this document
  describes) does not configure `server.ssl.*` to actually terminate an mTLS listener with it -
  see "known limitations" below, and the comment above the `control-plane-tls` volume mount in
  `deployment.yaml` for the full reasoning.

The certificate chain itself: a self-signed cert-manager `ClusterIssuer` bootstraps one CA
`Certificate`; a namespaced `Issuer` is derived from that CA's secret; the control plane's
certificate (`usages: [server auth]`, DNS SANs matching its in-cluster Service name) and the
worker's certificate (`usages: [client auth]`, no DNS SAN - nothing dials a worker by hostname)
are both issued by that derived `Issuer`. `Fabric8JobLauncher` does not yet mount the worker
certificate Secret into the Jobs it creates - also called out in "known limitations".

## 6. The JVM in a container: `MaxRAMPercentage=75`, and why not 100

`control-plane/Dockerfile` sets `-XX:MaxRAMPercentage=75 -XX:+UseG1GC
-XX:+ExitOnOutOfMemoryError` in `JAVA_TOOL_OPTIONS`. RUNBOOK §2 is the full incident writeup, with
real measured numbers on the machine this was developed on (15 GiB host, JDK 21):

```
MaxHeapSize with no flags at all, on a 15 GiB host:            3581935616  (~3.5 GiB - 25% of the HOST)
MaxHeapSize with -XX:MaxRAM=512m (simulating a 512Mi limit):     134217728  (128 MiB - 25% of the LIMIT)
MaxHeapSize with the same limit + MaxRAMPercentage=75:           402653184  (384 MiB - 75% of the LIMIT)
```

Two distinct, opposite failure modes fall out of that table, and both are worth naming precisely
because they look similar from the outside (a pod that gets OOM-killed) but have opposite fixes:

1. **The JVM can't see the container's limit at all** (pre-8u191, or `-UseContainerSupport`
   disabled) and sizes its heap off the *host's* memory - in a 512Mi container on a 15 GiB node
   that's a 3.5 GiB heap ceiling, and the kernel cgroup OOM-kills the process the moment it grows
   past 512Mi, with **no Java stack trace at all** (this is the tell that distinguishes a
   container OOMKill from an application `OutOfMemoryError` - see RUNBOOK §2's comparison table).
2. **The JVM sees the limit correctly and is too conservative about it** - the modern default,
   25% of the container limit, wastes 384 of a 512Mi limit and starts GC-thrashing early, which
   reads like a performance problem, not a configuration one, until someone thinks to check.

`MaxRAMPercentage=75` fixes the second failure mode without reintroducing the first: 75% is set
as a fraction of whatever the container's *actual* cgroup limit is (correctly detected, unlike
failure mode 1), leaving 25% of headroom for what doesn't live on the heap at all - metaspace,
thread stacks (~1 MiB each; 200 Tomcat threads is 200 MiB by itself), the JIT's code cache, GC
bookkeeping structures, and direct byte buffers. **Why not closer to 100%:** at 90%+, the process
gets OOM-killed by *native* memory pressure while the heap graph looks completely healthy - which
reads as an unexplained crash rather than a sizing problem, exactly the kind of confusing
after-the-fact investigation this flag exists to prevent. `charts/agentplane/values.yaml`'s
default `resources.limits.memory: 512Mi` is chosen to match this exact worked example, not an
arbitrary number - 512Mi limit × 75% = 384Mi heap, the RUNBOOK's own measured result.

**Why G1 and not the default for this heap size:** below roughly 256 MiB of heap, the JVM's own
heuristic picks SerialGC, which has lower overhead at that scale. `UseG1GC` is set explicitly here
because this is a request-serving service holding open SSE connections - G1's pause-time target
matters more than raw throughput, since a long stop-the-world pause is felt by every connected
console simultaneously, not smoothed out the way it would be for a batch job.
`ExitOnOutOfMemoryError` closes the loop: without it, a heap-exhausted JVM can limp along, failing
individual requests while its liveness probe (which must never itself depend on the state this
flag is reacting to - see §7 below) keeps passing. Better to die visibly and let Kubernetes
restart a genuinely broken pod.

## 7. Probes: why liveness must never include a dependency check

RUNBOOK §6 is the full incident writeup; the short version, because it is the single most
consequential Kubernetes-specific decision in this chart:

| Probe | Question it answers | Endpoint | On failure |
|---|---|---|---|
| **Liveness** | Is this process wedged? | `/actuator/health/liveness` | Kill and restart the pod |
| **Readiness** | Can it serve traffic *right now*? | `/actuator/health/readiness` | Pull out of Service rotation, don't kill |
| **Startup** | Has the JVM finished booting yet? | `/actuator/health/liveness`, generous budget | Nothing yet - suppresses liveness/readiness until this passes |

Spring Boot's default *readiness* group includes the datasource/Mongo/Redis health indicators -
correctly so, because "a dependency is briefly unreachable" should pull a pod out of load-balancer
rotation without killing it. The mistake `charts/agentplane` deliberately avoids is pointing
*liveness* at that same readiness check (or anything that touches a dependency): if it did, a
brief database blip would fail liveness on **every pod simultaneously**, Kubernetes would restart
the entire fleet at once, and a two-second network hiccup becomes a self-inflicted,
minutes-long restart storm - the exact failure `charts/agentplane/values.yaml`'s
`controlPlane.probes` comments (and the Deployment template) call out by name. Liveness here only
ever asks "is this process wedged", which a database being briefly down does not make true.

The `startupProbe` (30 × 5s = 150 seconds, in `values.yaml`) exists so a JVM's boot time (class
loading, Spring context initialization, Flyway migration) is never mistaken for a hang - liveness
and readiness are both suppressed until the startup probe itself passes once.

## 8. Multi-tenancy: namespace-per-tenant

Every `Tenant` gets its own Kubernetes namespace (`"tenant-" + slug`,
`TenantProvisioningService.createTenant`), with a `ResourceQuota` (CPU/memory requests and
limits, from the tenant's own `quotaCpu`/`quotaMemory` columns) and a `LimitRange` (per-container
CPU/memory defaults, so a workload spec that omits resource requests/limits entirely still gets
sane ones rather than being unbounded) - `Fabric8JobLauncher.provisionTenantNamespace`. This is
the actual mechanism behind "one tenant cannot starve another": it is enforced by Kubernetes
itself at admission time, not by anything the control plane has to remember to check before
launching a `Job`. `charts/agentplane/templates/tenants.yaml` mirrors the exact same naming/labels
statically, for pre-provisioning known tenants via GitOps rather than only through the API - see
that template's own comment for why the two are kept in lockstep rather than diverging into two
different ideas of what a tenant's namespace looks like.

## 9. Known limitations

Stated plainly, in the same spirit as RUNBOOK.md's own "this was reasoned through, not
reproduced" caveats:

- **`NoopJobLauncher` is a simulation, not a substitute for a real cluster run.** Every run
  observed via `docker compose up`/`make up` in this repository's default configuration is a
  scheduled in-process callback (SCHEDULED → RUNNING → SUCCEEDED/FAILED, ~90% simulated success
  rate), not a real Kubernetes `Job`. `Fabric8JobLauncher` exists and is exercised by unit tests
  with a mocked `KubernetesClient`, but **no run against a live cluster was performed as part of
  building this** - `charts/agentplane`, `infra/`, and the worker were built and reviewed
  carefully, but the full path (control plane → real `Job` → real worker pod → mTLS callback)
  has not been end-to-end verified against an actual AKS cluster.
- **The worker's status-report endpoint does not exist on the control plane.**
  `worker/src/agentplane_worker/control_plane_client.py` implements and tests a complete mTLS
  client posting to `/api/v1/internal/runs/{id}/status` - that endpoint is not implemented in
  `control-plane/src` (out of scope for this change). Today, a real cluster's run status is
  observed the other way around: `orchestration.PodStatusWatcher` polls pod phase via the
  Kubernetes API. The worker's callback is a complete, tested implementation of half of a
  contract the control plane doesn't yet expose the other half of.
- **The control plane does not terminate an mTLS listener.** See §5 above -
  `charts/agentplane` issues and mounts a real server certificate; `control-plane/src` doesn't
  have `server.ssl.*` configured to use it. The Ingress, the actuator health probes, and the
  Service in this chart all still assume plain HTTP, which is what's actually implemented and
  tested.
- **`Fabric8JobLauncher` does not mount the worker's client certificate into the Jobs it
  creates.** `charts/agentplane` issues and rotates `agentplane-worker-client-tls`; the Java code
  that builds a worker Job's `PodSpec` (`Fabric8JobLauncher.launchJob`) only sets run-spec env
  vars today, no volumes.
- **`IDEMPOTENCY_KEY` is not injected by `Fabric8JobLauncher`.** The worker honours an
  `IDEMPOTENCY_KEY` env var and falls back to `AGENTPLANE_RUN_ID` if it's absent (see
  `worker/src/agentplane_worker/run_spec.py`) - which is safe for the case that actually matters
  (a redelivered message for the *same* run), but a control-plane change to pass an explicit key
  through would remove the reliance on that fallback being correct forever.
- **No live-cluster validation of `charts/agentplane` or `infra/`.** Neither `helm` nor
  `terraform` binaries were available in the sandbox this was built in (outbound access is
  restricted to an allowlist that doesn't include `get.helm.sh` or the Terraform release CDN).
  What *was* validated: `helm template`'s Go-template braces are balanced and every `.tf` file
  parses as syntactically valid HCL (`python-hcl2`) - neither is a substitute for `helm lint` /
  `terraform validate` actually running against the real tool and provider schemas, and both
  should be run for real before trusting this against a live cluster/subscription.
- **`infra/` has no backup/DR story.** No point-in-time-restore configuration beyond provider
  defaults, single-region only, no cross-region failover for Postgres/Cosmos/Redis - see
  `infra/README.md`'s own "not covered here" section.
- **The NetworkPolicy CIDR values in `values.yaml` are placeholders.** `networkPolicy.postgres/
  mongo/redis/kubernetesApi.cidr` ship permissive, illustrative defaults specifically so
  `helm lint`/`helm template` succeed out of the box - they are not the real private-endpoint
  ranges for any actual cluster, and `values-prod.yaml` says so explicitly in its own comments.
- **The control-plane → worker `NetworkPolicy` allow rule is scaffolding, not load-bearing.**
  See `charts/agentplane/values.yaml`'s `networkPolicy.controlPlaneToWorker` comment: nothing in
  `control-plane/src` today dials a worker pod directly (cancellation goes through the Kubernetes
  API, deleting the `Job`) - the rule anticipates a future direct callback path rather than
  describing one that exists.
