# 0004 - mTLS between the control plane and workers

**Date:** 2026-08-26
**Status:** Accepted (control-plane-side termination not yet implemented - see Consequences)

## Context

A worker Job pod reports its run's outcome back to the control plane over the network, crossing
a tenant's own namespace boundary to do so. One-way TLS (verifying only the control plane's
identity) would stop a worker from being fooled by an impersonating control plane, but would not
stop something else in the cluster from posing as a legitimate worker and reporting a fabricated
status for someone else's run - the control plane also needs to know *which* worker it's talking
to.

## Decision

Mutual TLS, both directions verified, backed by a private cert-manager-issued CA rather than a
public one (this PKI only ever needs to be trusted by this cluster's own workloads):

- A self-signed cert-manager `ClusterIssuer` bootstraps one CA `Certificate`; a namespaced
  `Issuer` derived from that CA's secret issues every leaf certificate
  (`charts/agentplane/templates/certificates.yaml`).
- The control plane's certificate: `usages: [server auth]`, DNS SANs matching its in-cluster
  Service name - it is the server in this relationship.
- The worker's certificate: `usages: [client auth]`, no DNS SAN - it authenticates outbound
  connections *from* itself, nothing ever dials it back by hostname.
- The worker's client (`worker/src/agentplane_worker/control_plane_client.py`) refuses to fall
  back to plaintext or unverified TLS under any circumstance, and distinguishes a TLS trust
  failure from an ordinary connection/timeout failure in its logging - mirroring the
  keystore/truststore distinction in RUNBOOK §4 ("`PKIX path building failed` is always a
  truststore problem").

## Consequences

- This is fully implemented and independently tested on the worker side
  (`worker/tests/test_control_plane_client.py`, against a real local HTTPS server requiring a
  client certificate, using certificates generated fresh per test run) - not a design that only
  exists on paper.
- It is **not** fully wired end to end. Two gaps, stated plainly rather than glossed over (see
  `docs/ARCHITECTURE.md`'s "known limitations" for the same list in context):
  1. `control-plane/src` does not configure `server.ssl.*` to terminate an mTLS listener with
     the certificate the chart mounts for it - the control plane serves plain HTTP today, which
     is what its probes/Ingress/Service all correctly assume.
  2. `Fabric8JobLauncher.launchJob` does not mount the worker's client certificate Secret into
     the Job pods it creates - it only sets run-spec env vars today.
- Both gaps require changes to `control-plane/src`, out of scope for the infrastructure layer
  this ADR covers. What's shipped instead is the complete PKI (issued, rotated, ready) and a
  fully working, tested client - the smallest change needed to close the gap is wiring
  `control-plane/src` up to material that already exists, not standing up new infrastructure.
- Certificate rotation is cert-manager's job (`renewBefore` on each `Certificate`), not something
  either application has to implement - but neither application has been verified to actually
  pick up a rotated certificate without a restart, since neither terminates TLS with it yet.
