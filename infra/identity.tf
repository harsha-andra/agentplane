# The control plane's own Azure identity - what it actually authenticates to Azure AD as when
# reading Key Vault. Distinct from AKS's SystemAssigned identity (aks.tf, which manages the
# cluster's own infrastructure) and from the kubelet identity (which only has AcrPull) - this is
# scoped to exactly one application.
resource "azurerm_user_assigned_identity" "control_plane" {
  name                = "id-${local.name_prefix}-control-plane"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = local.default_tags
}

# The federated identity credential is the whole mechanism: it tells Azure AD "trust a Kubernetes
# service-account token for system:serviceaccount:<namespace>:<name>, issued by THIS cluster's
# OIDC issuer, as proof of this identity" - no client secret or certificate is ever generated or
# stored for it. The azure-workload-identity mutating webhook (installed as part of AKS's
# workload_identity_enabled feature) is what actually projects a short-lived, auto-rotated token
# into the pod matching this subject, when the pod's ServiceAccount carries the
# `azure.workload.identity/use: "true"` label and `azure.workload.identity/client-id` annotation
# (see charts/agentplane/values-prod.yaml's controlPlane.serviceAccount block).
resource "azurerm_federated_identity_credential" "control_plane" {
  name                = "fic-${local.name_prefix}-control-plane"
  resource_group_name = azurerm_resource_group.this.name
  parent_id           = azurerm_user_assigned_identity.control_plane.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.this.oidc_issuer_url
  subject             = "system:serviceaccount:${var.control_plane_namespace}:${var.control_plane_service_account_name}"
}
