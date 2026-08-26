# agentplane Helm chart

Deploys the AGENTPLANE control plane onto Kubernetes: Deployment/Service/Ingress/HPA/PDB/
ServiceMonitor, the cert-manager-issued mTLS PKI backing the control-plane/worker relationship,
default-deny NetworkPolicy plus explicit allows, per-tenant Namespace/ResourceQuota/LimitRange,
and the RBAC the control plane needs to launch Kubernetes Jobs.

## Prerequisites

- cert-manager (for `templates/certificates.yaml`) - or set `certManager.enabled=false`.
- ingress-nginx (for `templates/ingress.yaml`) - or set `controlPlane.ingress.enabled=false`.
- Prometheus Operator, if `serviceMonitor.enabled=true` (default) - the ServiceMonitor template
  quietly no-ops instead of failing if the CRD is not registered (see that template's comment).

## Install

```bash
helm lint charts/agentplane
helm template agentplane charts/agentplane -f charts/agentplane/values.yaml -f charts/agentplane/values-dev.yaml

helm upgrade --install agentplane charts/agentplane \
  -n agentplane --create-namespace \
  -f charts/agentplane/values.yaml \
  -f charts/agentplane/values-dev.yaml   # or values-prod.yaml
```

## What's deliberately NOT wired end to end

This chart provisions real mTLS material (a self-signed CA, a server certificate for the
control plane, a client certificate for workers) via cert-manager, and it is tested for
correctness the way `worker/` uses it - but two things it does *not* do, on purpose, because
they require changes to `control-plane/src` (out of scope for this change):

1. The control plane does not configure `server.ssl.*` to actually terminate an mTLS listener
   with the mounted certificate - see the comment above `control-plane-tls` in
   `templates/deployment.yaml`.
2. `Fabric8JobLauncher` does not mount the worker client certificate Secret into the Job pods it
   creates - the chart issues and rotates `agentplane-worker-client-tls`, ready for that wiring.

See `docs/ARCHITECTURE.md`, "known limitations", for the full list.

## Values files

- `values.yaml` - defaults, meant to be layered under an environment file, not applied alone.
- `values-dev.yaml` - one replica, HPA/PDB/ServiceMonitor/NetworkPolicy off, for fast local
  iteration against a throwaway cluster.
- `values-prod.yaml` - 3+ replicas, HPA/PDB/NetworkPolicy on, AKS workload-identity annotations
  wired for the ServiceAccount (paired with `infra/`'s federated identity credential).
