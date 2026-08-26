# AGENTPLANE control plane

A Kubernetes control plane for LLM agent workloads. It accepts a run request, persists it,
queues it, launches it as a Kubernetes `Job` (or simulates that locally with no cluster at all),
tracks its lifecycle, streams live status/log events over SSE, and exposes both operational
(Postgres-backed) and analytical (MongoDB aggregation) views over the result.

## What it does

- **`POST /api/v1/runs`** validates a run spec, persists an `AgentRun` (`PENDING`), writes an
  append-only audit event, and publishes the run onto a Redis Stream.
- A **stream consumer** (consumer group, not pub/sub) dequeues the message, guards against
  double-launch with a Redis-backed idempotency key, and hands off to a `JobLauncher` that either
  creates a real Kubernetes `Job` or simulates one in-process.
- The run's status (`PENDING → SCHEDULED → RUNNING → SUCCEEDED/FAILED/CANCELLED/TIMED_OUT`) is
  broadcast over **SSE** (`GET /api/v1/runs/{id}/events`) as it changes, along with simulated/real
  log lines.
- Raw execution traces (tool calls, latencies, errors) are written to **MongoDB** and rolled up
  into per-tool latency/error-rate stats via a real aggregation pipeline.
- Every tenant gets its own Kubernetes **namespace + `ResourceQuota` + `LimitRange`** on creation,
  so one customer's workloads cannot starve another's.

## Running it locally (no Kubernetes required)

```bash
docker compose up --build
```

This starts Postgres, MongoDB, Redis and the app itself (profile `local`), with
`agentplane.k8s.enabled=false` - the default. Then:

- API base: `http://localhost:8080/api/v1`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Actuator health: `http://localhost:8080/actuator/health`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`

To also seed 5 tenants and ~80 runs (with traces) so there's something to look at immediately:

```bash
SPRING_PROFILES=local,seed docker compose up --build
```

### Running without Docker Compose

Point a local Postgres/MongoDB/Redis at the defaults (`localhost:5432` / `localhost:27017` /
`localhost:6379`, or override via the env vars in the config table below), then:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local,seed
```

### Pointing it at a real Kubernetes cluster

Set `AGENTPLANE_K8S_ENABLED=true` with a reachable cluster (in-cluster service account, or a
local kubeconfig). This switches `JobLauncher` from `orchestration.NoopJobLauncher` (which
simulates run execution with delayed in-process callbacks) to `orchestration.Fabric8JobLauncher`
(which creates real `Job`/`Namespace`/`ResourceQuota`/`LimitRange` objects via fabric8), and turns
on the pod-status watcher that polls real pod phase instead.

## Endpoints

| Method | Path | Description |
|---|---|---|
| `GET`  | `/api/v1/runs` | Paginated, filterable (`status`, `tenantId`, `q`) list of runs |
| `GET`  | `/api/v1/runs/{id}` | Full run detail (spec, pod status, token usage, cost) |
| `POST` | `/api/v1/runs` | Submit a new run (`201`) |
| `POST` | `/api/v1/runs/{id}/cancel` | Cancel a run |
| `GET`  | `/api/v1/runs/{id}/events` | Live SSE stream of status transitions + log lines |
| `GET`  | `/api/v1/runs/{id}/traces` | Raw execution trace for a run (from MongoDB) |
| `GET`  | `/api/v1/tenants` | List tenants |
| `POST` | `/api/v1/tenants` | Create a tenant (`201`) - provisions its namespace/quota |
| `GET`  | `/api/v1/analytics/tool-latency?days=7` | Per-tool call volume, avg/p95 latency, error rate |
| `GET`  | `/api/v1/analytics/overview` | Dashboard overview (active/queued runs, success rate, etc.) |

Full request/response schemas: `/swagger-ui.html` (backed by `/v3/api-docs`). Errors are RFC 7807
`ProblemDetail` bodies (`type`, `title`, `status`, `detail`, and `errors` for validation failures).

## Polyglot persistence: why Postgres *and* MongoDB

> "Postgres holds anything that needs transactions and joins - customers, job records, audit log.
> MongoDB holds the raw run logs, which arrive in huge volume and have no fixed shape."

- **Postgres** (`domain` package, Flyway-migrated, `ddl-auto=validate`): `Tenant`, `AgentRun`,
  `AuditEvent`. These need joins (a run belongs to a tenant), transactional integrity (optimistic
  locking via `@Version` on `AgentRun` - the API handler, the pod watcher and the stream consumer
  all touch a run concurrently), and relational querying (list runs by tenant/status/time range).
  `AuditEvent` is append-only by construction: no setters, every column `updatable = false`.
- **MongoDB** (`trace` package): `RunTrace` - one document per log line/tool call/step. This data
  arrives in far higher volume than run records, and its `payload` shape varies per tool with no
  fixed schema, so a relational table (and a migration for every new field) would be the wrong
  tool. `TraceAnalyticsService` computes avg/p95 latency and error rate per tool with a genuine
  aggregation pipeline (`$match → $group → $project → $sort`), not a Java stream over fetched
  documents - see that class's Javadoc for the reasoning, including why `$unwind` is used
  elsewhere (tag-frequency counting) but deliberately *not* in the tool-latency pipeline (it would
  fan out documents and corrupt the average).

## Redis Streams + idempotency

> "Jobs are queued in Redis Streams. If a worker dies halfway through, another worker picks the
> job up and retries it without running any step twice."

`RunStreamProducer` publishes to a Redis Stream; `RunStreamConsumer` reads it through a **consumer
group** (`XREADGROUP` semantics via Spring Data Redis's `StreamOperations`), so multiple worker
instances share one queue and each message is delivered to exactly one consumer at a time. A
message is only removed from the group's pending-entries list once processing completes and
`XACK`s it. If a worker dies mid-processing, the message stays pending; a scheduled reclaim job
(`RunStreamConsumer#reclaimStaleEntries`) finds entries idle past
`agentplane.stream.visibility-timeout-ms` and reclaims them via `XCLAIM` for retry.
`stream.IdempotencyGuard` (Redis `SETNX`, i.e. `setIfAbsent`) then guards the actual
side-effecting work (launching the Kubernetes Job) so a redelivered/retried message never
re-launches a job that a previous, half-finished attempt already created.

## Kubernetes orchestration without a cluster

`orchestration.JobLauncher` is the only thing that talks to Kubernetes. Two implementations:

- `Fabric8JobLauncher` - real fabric8 client calls, active only when `agentplane.k8s.enabled=true`.
- `NoopJobLauncher` - the **default**. Simulates a run's lifecycle with scheduled in-process
  callbacks (SCHEDULED → RUNNING → SUCCEEDED/FAILED) and a few fake log lines, so the entire
  application - API, persistence, streaming, SSE, analytics - is fully explorable with nothing
  but `docker compose up`.

Neither the `KubernetesClient` bean nor `Fabric8JobLauncher` are even constructed unless
`agentplane.k8s.enabled=true` (see `config.KubernetesClientConfig`), so there's no risk of the
app trying (and failing) to reach a cluster that isn't there.

## Configuration

| Property / env var | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/agentplane` | Postgres JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `agentplane` / `agentplane` | Postgres credentials |
| `SPRING_DATA_MONGODB_URI` | `mongodb://localhost:27017/agentplane` | MongoDB connection URI |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | `localhost` / `6379` | Redis connection |
| `AGENTPLANE_K8S_ENABLED` (`agentplane.k8s.enabled`) | `false` | Real fabric8 client vs. Noop simulation |
| `agentplane.k8s.pod-watch-interval-ms` | `5000` | Pod-status poll interval (Fabric8 mode only) |
| `agentplane.stream.stream-key` | `agentplane:runs` | Redis Stream key for the run queue |
| `agentplane.stream.consumer-group` | `agentplane-workers` | Redis Stream consumer group name |
| `agentplane.stream.poll-timeout-ms` / `-batch-size` | `2000` / `10` | Consumer long-poll timeout / batch size |
| `agentplane.stream.visibility-timeout-ms` | `60000` | How long a claimed message may go un-acked before another worker reclaims it |
| `agentplane.stream.idempotency-ttl-seconds` | `3600` | TTL of the Redis launch-idempotency guard key |
| `agentplane.mongo.ensure-indexes-on-startup` | `true` | Create MongoDB indexes for `RunTrace` at boot |
| `agentplane.sse.heartbeat-interval-ms` | `15000` | SSE heartbeat comment interval |

## Testing

`mvn clean verify` runs the **fast, infra-free** suite: run state machine transitions, the
idempotency guard, MapStruct mappers, Bean Validation, the `NoopJobLauncher` simulated lifecycle,
`RunOrchestrationService`/`RunLaunchProcessor`/`RunTransitionService`/`TenantProvisioningService`
(all mocked), the `ProblemDetail` exception advice, and a full `@SpringBootTest` context-load
smoke test (`test` profile: in-memory H2 instead of Postgres, Mongo/Redis-touching schedulers
switched off - see `application-test.yml`). None of this needs Docker.

Testcontainers-backed integration tests (the real Postgres schema/repositories, and the real
MongoDB aggregation pipeline) are tagged `@Tag("integration")` and excluded from the default run
via the surefire `excludedGroups` config in `pom.xml`. Run them explicitly (Docker required):

```bash
mvn verify -Pintegration
```

## Package layout

```
config/          K8s client (conditional), Redis/Mongo config, OpenAPI, async/scheduling, metrics, health
domain/          JPA entities + repositories (Postgres): Tenant, AgentRun, AuditEvent
trace/           MongoDB RunTrace document + repository + aggregation analytics
orchestration/   JobLauncher (Fabric8/Noop), run state machine, submission/cancellation/launch services
stream/          Redis Streams producer + consumer-group listener, idempotency guard
sse/             SseEmitter registry + run event broadcasting
api/             REST controllers + DTOs + MapStruct mappers + RFC 7807 exception advice
seed/            `seed`-profile CommandLineRunner (5 tenants, ~80 runs, traces)
```

## Container image

Multi-stage build (`Dockerfile`): Maven build stage, then a JRE 21 (`eclipse-temurin:21-jre-jammy`)
runtime stage running as a non-root user, with a `HEALTHCHECK` against `/actuator/health`.
`JAVA_TOOL_OPTIONS` sets `-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` - see
the comment above it in the Dockerfile for the container-memory-visibility postmortem this
addresses (the JVM sizing its heap off the host's memory instead of the container's cgroup limit,
leading to OOM-kills long before the JVM itself thought it was under pressure).
