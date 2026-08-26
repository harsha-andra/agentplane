# 0002 - Polyglot persistence: Postgres + MongoDB

**Date:** 2026-08-26
**Status:** Accepted

## Context

AGENTPLANE has two structurally different kinds of data. `Tenant`/`AgentRun`/`AuditEvent` are
low-volume, fixed-shape, relationally-queried, and need transactional integrity - a run belongs
to exactly one tenant (a real foreign key), several actors touch the same `AgentRun` row
concurrently (the API handler, `PodStatusWatcher`, the stream consumer), and the runs list/
dashboard filter and sort across tenant/status/time range. `RunTrace` (one document per log
line/tool call/step an agent produces) is the opposite: high-volume relative to run records,
append-only, and its `payload` shape differs per tool and evolves as new tools/agent frameworks
are integrated, with no natural fixed schema.

## Decision

Two databases, chosen for what each half of the data actually is, not for novelty:

- **PostgreSQL** for `Tenant`, `AgentRun`, `AuditEvent` (`domain` package, Flyway-migrated,
  `ddl-auto=validate`). `AgentRun` uses `@Version` optimistic locking specifically because of the
  concurrent-writer problem above. `AuditEvent` is append-only by construction (no setters, every
  column `updatable = false`).
- **MongoDB** for `RunTrace` (`trace` package). Schemaless `payload: Map<String, Object>` so a
  new tool's trace shape never needs a migration. Rollups (`TraceAnalyticsService`) are computed
  with a real aggregation pipeline (`$match → $group → $project → $sort`), not documents pulled
  into the JVM and folded with a Java stream - see `docs/ARCHITECTURE.md` §2 for exactly what that
  pipeline computes and why `$percentile` (Mongo 7+) is deliberately avoided in favor of computing
  p95 in application code over each group's small, already-aggregated latency array.

## Consequences

- Adding a new tool with a different trace payload shape is a zero-migration change on the Mongo
  side; the same change to `AgentRun`'s fixed columns would need a Flyway migration, which is the
  right amount of friction for data that genuinely does need a fixed schema.
- Two databases means two connection pools, two health indicators, two backup/restore stories,
  and two things that can be down independently - `readiness` (RUNBOOK §6) correctly reflects
  either being unreachable, at the cost of more moving parts than a single-database design.
- Not every analytics endpoint honors this split equally cleanly - `AnalyticsController.overview
()`'s 7-day `runsOverTime` bucketing is computed in Java over a bounded JPA query result, not a
  SQL `GROUP BY`. That's a reasonable choice at "last 7 days for one dashboard", not evidence that
  in-memory aggregation is trusted at this codebase's actual analytics volume - see
  `docs/ARCHITECTURE.md` §2 for the honest version of this rather than glossing over it.
