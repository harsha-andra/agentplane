# infra/ - Terraform for Azure

Provisions everything `charts/agentplane` needs to run against real infrastructure: an AKS
cluster (workload identity + OIDC issuer enabled), Azure Database for PostgreSQL Flexible Server
(VNet-integrated), Azure Cache for Redis, Cosmos DB (Mongo API), Key Vault, and an ACR - wired
together so a pod authenticates to Key Vault via workload identity, with **no secret in any
manifest, anywhere**.

## Authentication - no hardcoded subscription/tenant IDs

`providers.tf` deliberately does not set `subscription_id`/`tenant_id`. Authenticate with the
Azure CLI first, and Terraform inherits that context automatically:

```bash
az login
az account set --subscription "<name-or-id>"      # picks the subscription for this session
terraform init
terraform plan  -var-file=dev.tfvars               # your own copy of example.tfvars, gitignored
terraform apply -var-file=dev.tfvars
```

(Equivalently, for CI: export `ARM_SUBSCRIPTION_ID`/`ARM_TENANT_ID`/`ARM_CLIENT_ID` - see
`.github/workflows/cd.yml`, which authenticates via OIDC federation instead of either of those
being a stored secret.)

## State is never committed

There is no `backend` block in `versions.tf` on purpose - see the comment there for exactly how
to point this at a real remote backend (an `azurerm` storage account, one per environment) via
`-backend-config`, instead of hardcoding one specific backend that every environment would then
share. Until you do, Terraform uses local state (`terraform.tfstate`), which - like every
`*.tfstate*` and every real `*.tfvars` file - is excluded by the root `.gitignore`. State contains
resource IDs and, for a couple of resources here, values Terraform read back from Azure (e.g. the
Postgres admin password is written into Key Vault, but Terraform's own state also necessarily
knows it - treat local state as sensitive and never commit it, exactly as `.gitignore` assumes.

## What this creates

| Resource | Purpose |
|---|---|
| `azurerm_kubernetes_cluster` | AKS, `oidc_issuer_enabled` + `workload_identity_enabled`, Azure CNI, Azure NetworkPolicy (enforces `charts/agentplane`'s NetworkPolicy objects), Key Vault Secrets Provider + Secrets Store CSI driver add-on |
| `azurerm_container_registry` | ACR; AKS's kubelet identity is granted `AcrPull` (no `imagePullSecrets` anywhere - see `docs/RUNBOOK.md` §3) |
| `azurerm_postgresql_flexible_server` | Postgres 16, VNet-integrated (delegated subnet, private DNS zone) - never publicly reachable |
| `azurerm_redis_cache` | Standard tier, TLS-only, reachable only via a Private Endpoint |
| `azurerm_cosmosdb_account` (Mongo API) | Stands in for MongoDB - control-plane speaks the Mongo wire protocol either way, no code difference; reachable only via a Private Endpoint |
| `azurerm_key_vault` | RBAC-authorized (not access policies); holds the Postgres password, the Postgres JDBC URL, the Mongo connection string, and the Redis key - nothing else in this configuration ever sees these values as literals |
| `azurerm_user_assigned_identity` + `azurerm_federated_identity_credential` | The control plane's workload identity, federated to `system:serviceaccount:<namespace>:<service-account>` - see `variables.tf`'s `control_plane_namespace`/`control_plane_service_account_name` |

## Wiring Key Vault into the cluster

Terraform provisions the vault, the secrets, and the workload identity's read access to them
(`azurerm_role_assignment.control_plane_kv_secrets_user` in `keyvault.tf`). The last mile - a
`SecretProviderClass` telling the Secrets Store CSI driver *which* secrets to mount, and to also
mirror them into a plain Kubernetes `Secret` the Deployment can reference via `secretEnv`
(`charts/agentplane/values.yaml`) - is cluster config, not infrastructure, so it is not a
Terraform resource. After `terraform apply`, apply something shaped like this (values filled in
from `terraform output`):

```yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: agentplane-keyvault-secrets
  namespace: agentplane
spec:
  provider: azure
  parameters:
    usePodIdentity: "false"
    useVMManagedIdentity: "false"
    clientID: "<control_plane_workload_identity_client_id output>"
    keyvaultName: "<key_vault_name output>"
    tenantId: "<az account show --query tenantId>"
    objects: |
      array:
        - |
          objectName: postgres-admin-password
          objectType: secret
        - |
          objectName: mongo-connection-string
          objectType: secret
        - |
          objectName: redis-primary-key
          objectType: secret
  secretObjects:
    - secretName: agentplane-keyvault-secrets   # the K8s Secret charts/agentplane's
      type: Opaque                              # controlPlane.secretEnv entries reference
      data:
        - objectName: postgres-admin-password
          key: postgres-password
        - objectName: mongo-connection-string
          key: mongo-connection-string
        - objectName: redis-primary-key
          key: redis-key
```

Mount this `SecretProviderClass` as a volume on the control-plane pod (any path - the CSI driver
only needs the mount to trigger the sync) and set `controlPlane.secretEnv` in
`charts/agentplane/values-prod.yaml` to read from the resulting `agentplane-keyvault-secrets`
Secret - see that file's commented example.

## `terraform plan` / `terraform apply` in practice

```bash
terraform init
terraform validate
terraform plan  -var-file=dev.tfvars -out=tfplan
terraform apply tfplan
```

Destroying is the reverse, and safe to do freely against a `dev` environment - nothing here holds
data worth keeping outside of a real Postgres/Mongo/Redis backup strategy, which this module does
not set up (see `docs/ARCHITECTURE.md`, "known limitations"):

```bash
terraform destroy -var-file=dev.tfvars
```

## Not covered here (be aware before treating this as production-ready)

- No backup/point-in-time-restore configuration for Postgres or Cosmos DB beyond the provider
  defaults.
- No Azure Monitor alerting - only the raw Log Analytics workspace AKS diagnostics land in.
- Single-region only; no cross-region failover for any datastore.
- The AKS node pool is a single system+user pool for simplicity - a real production cluster
  would split system and user (workload) node pools.
