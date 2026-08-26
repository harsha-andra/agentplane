# 0005 - The `JobLauncher` abstraction

**Date:** 2026-08-26
**Status:** Accepted

## Context

Talking to Kubernetes (creating `Job`/`Namespace`/`ResourceQuota`/`LimitRange` objects, watching
pod status) is the one thing in this codebase that categorically cannot be exercised without
either a real cluster or a very committed set of mocks reaching deep into a Kubernetes client
library. Requiring a cluster to run the application at all - even for local development, even for
the unit test suite - would mean nobody can clone this repository and see it work without first
standing up Kubernetes, and would mean the fast test suite either doesn't cover orchestration at
all or is slow and flaky against a real (or fake, e.g. `envtest`) API server.

## Decision

One interface, `orchestration.JobLauncher`, is the *only* thing in the control plane that knows
Kubernetes exists:

```java
public interface JobLauncher {
    void provisionTenantNamespace(Tenant tenant);
    LaunchedJob launchJob(AgentRun run, Tenant tenant);
    void cancelJob(AgentRun run);
    Optional<PodStatusSnapshot> getPodStatus(AgentRun run);
}
```

Two implementations, selected by `agentplane.k8s.enabled`
(`config.KubernetesClientConfig`'s `@ConditionalOnProperty`, which also gates whether the fabric8
`KubernetesClient` bean is constructed at all):

- `Fabric8JobLauncher` - real fabric8 client calls, active only when the property is `true`.
- `NoopJobLauncher` - the default. Simulates a run's lifecycle with scheduled in-process
  callbacks instead of a real `Job` (SCHEDULED → RUNNING → SUCCEEDED/FAILED, ~90% simulated
  success rate, a few fake log lines).

Every other part of the control plane (`RunLaunchProcessor`, `PodStatusWatcher`,
`TenantProvisioningService`) depends only on the interface.

## Consequences

- `docker compose up`/`make up` runs the entire application - API, persistence, Redis Streams,
  SSE, analytics - with no Kubernetes cluster anywhere, because `NoopJobLauncher` stands in for
  the one piece that would otherwise need one. This is why a reviewer can clone this repository
  and have a working system in the time a container build takes.
- The 71-test unit suite (`control-plane/README.md`, "Testing") exercises orchestration logic
  (`RunLaunchProcessor`, `TenantProvisioningService`, the run state machine) against a mocked
  `JobLauncher`, with no Testcontainers, no cluster, no network - fast and deterministic by
  construction, not by carefully avoiding the orchestration code paths.
- The cost: `Fabric8JobLauncher` itself - the actual Kubernetes API calls - is covered by unit
  tests with a mocked `KubernetesClient`, not by a run against a live cluster. See
  `docs/ARCHITECTURE.md`'s "known limitations": no end-to-end run against a real AKS cluster was
  performed as part of this change. The abstraction makes that gap easy to see and easy to close
  later (swap in an integration test against a real or `kind`/`envtest` cluster behind the same
  interface) - it does not close it by itself.
- Anything that needs to reach into Kubernetes for a *new* reason (e.g. reading a ConfigMap, or
  watching Events) has to go through this interface too, or the "runs with nothing" property
  quietly stops being true for whatever new feature bypasses it.
