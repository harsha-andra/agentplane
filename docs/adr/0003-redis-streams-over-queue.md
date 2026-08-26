# 0003 - Redis Streams over a dedicated queue/broker

**Date:** 2026-08-26
**Status:** Accepted

## Context

A submitted run needs to be queued and then picked up by exactly one worker (control-plane
instance) for launch, with the queue surviving a worker crashing mid-processing. AGENTPLANE
already runs Redis for other purposes (the launch-idempotency guard), so the question was
whether to add a dedicated broker (RabbitMQ, Kafka, SQS-equivalent) for this one queue or use a
capability Redis itself already provides.

## Decision

Redis Streams, consumed through a **consumer group** (`XREADGROUP` semantics, via Spring Data
Redis's `StreamOperations` - not a raw pub/sub subscription, which has no persistence and no
redelivery). `RunStreamProducer` publishes; `RunStreamConsumer` reads via
`agentplane.stream.consumer-group`, processes (`RunLaunchProcessor.processQueuedRun`), and only
then `XACK`s. A message a consumer read but never acknowledged sits in that consumer's Pending
Entries List (PEL) until `RunStreamConsumer.reclaimStaleEntries` (a scheduled job, threshold
`agentplane.stream.visibility-timeout-ms`) reclaims it via `XCLAIM`/`XAUTOCLAIM` for a live
consumer to retry. See `docs/ARCHITECTURE.md` §3 and RUNBOOK §8 (a real captured
`XPENDING`/`XINFO GROUPS` session) for the full mechanics.

Reclaim only works safely because of `stream.IdempotencyGuard` (Redis `SETNX`): a reclaimed,
redelivered message's actual side effect (launching a Kubernetes `Job`) is guarded so a second
attempt becomes a no-op rather than a second launch.

## Consequences

- One fewer piece of infrastructure to run/patch/monitor than adding a dedicated broker - Redis
  is already a hard dependency for the idempotency guard, so this reuses it rather than adding a
  second messaging system for one queue.
- The trade-off against a purpose-built broker: Redis Streams' consumer-group model is less
  battle-tested at very high fan-out/partition counts than Kafka's, and there is no built-in
  dead-letter queue - a message that keeps failing on every reclaim just keeps getting reclaimed
  forever unless something else notices (this system relies on `RunLaunchProcessor` logging and
  the run eventually timing out via `AgentRun.timeoutSeconds`/`PodStatusWatcher.checkTimeout`,
  not an explicit max-redelivery-count cutoff).
- The visibility timeout (`agentplane.stream.visibility-timeout-ms`, default 60s) is a single
  tunable trade-off between "steal work from a merely-slow-but-alive worker" (too short) and "a
  customer waits the full timeout after a crash before anything retries" (too long) - see
  RUNBOOK §8's "choosing the visibility timeout".
- Because idempotency is what makes reclaim safe, adding a new side effect to
  `RunLaunchProcessor.processQueuedRun` in the future has to be evaluated against "is this safe to
  run twice", not assumed safe by default.
