# ACR name: alphanumeric only, globally unique, 5-50 chars.
resource "azurerm_container_registry" "this" {
  name                = "acr${replace(var.project, "-", "")}${var.environment}${local.unique_suffix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  sku                 = "Standard"
  admin_enabled       = false # authenticate via the AKS kubelet identity (AcrPull role
                              # assignment in aks.tf) or CI's OIDC-federated identity
                              # (.github/workflows/cd.yml) - never the registry's admin
                              # username/password.
  tags                = local.default_tags
}
