resource "azurerm_log_analytics_workspace" "aks" {
  name                = "log-${local.name_prefix}-aks"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  sku                 = "PerGB2018"
  retention_in_days   = 30
  tags                = local.default_tags
}

resource "azurerm_kubernetes_cluster" "this" {
  name                = "aks-${local.name_prefix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  dns_prefix          = "aks-${local.name_prefix}"
  kubernetes_version  = var.aks_kubernetes_version

  default_node_pool {
    name           = "system"
    node_count     = var.aks_node_count
    vm_size        = var.aks_node_vm_size
    vnet_subnet_id = azurerm_subnet.aks.id
  }

  # SystemAssigned: AKS's own control-plane identity (manages the node resource group, LB, etc.)
  # - distinct from the control plane APPLICATION's identity, which is
  # azurerm_user_assigned_identity.control_plane in identity.tf.
  identity {
    type = "SystemAssigned"
  }

  # The two settings this whole infra module exists to demonstrate: an OIDC issuer AKS exposes
  # for its cluster, and workload identity - together these let a pod prove "I am ServiceAccount
  # X in namespace Y of cluster Z" to Azure AD via a short-lived, auto-rotated token projected
  # into the pod (no secret, no kubelet-wide managed identity shared by everything in the
  # cluster). See identity.tf for the federated identity credential this pairs with, and
  # docs/adr/ for why this replaces "a Key Vault connection string in a Kubernetes Secret".
  oidc_issuer_enabled      = true
  workload_identity_enabled = true

  key_vault_secrets_provider {
    # Also enables the Secrets Store CSI Driver add-on (a prerequisite for the
    # SecretProviderClass documented in infra/README.md, "Wiring Key Vault into the cluster").
    secret_rotation_enabled  = true
    secret_rotation_interval = "2m"
  }

  oms_agent {
    log_analytics_workspace_id = azurerm_log_analytics_workspace.aks.id
  }

  network_profile {
    network_plugin = "azure"
    network_policy = "azure" # enforces the NetworkPolicy objects charts/agentplane ships
  }

  tags = local.default_tags
}

# ACR pull rights for the cluster's own kubelet identity - no imagePullSecrets anywhere, per
# docs/RUNBOOK.md §3's "attach the registry to the cluster identity" fix.
resource "azurerm_role_assignment" "aks_acr_pull" {
  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
}
