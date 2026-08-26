output "resource_group_name" {
  value = azurerm_resource_group.this.name
}

output "aks_cluster_name" {
  value = azurerm_kubernetes_cluster.this.name
}

output "aks_get_credentials_command" {
  description = "Run this, then `helm upgrade --install ...` against charts/agentplane."
  value       = "az aks get-credentials --resource-group ${azurerm_resource_group.this.name} --name ${azurerm_kubernetes_cluster.this.name}"
}

output "aks_oidc_issuer_url" {
  description = "The issuer identity.tf's federated identity credential trusts - sanity-check this against `az aks show --query oidcIssuerProfile.issuerUrl` if workload identity ever stops authenticating."
  value       = azurerm_kubernetes_cluster.this.oidc_issuer_url
}

output "acr_login_server" {
  value = azurerm_container_registry.this.login_server
}

output "key_vault_name" {
  value = azurerm_key_vault.this.name
}

output "key_vault_uri" {
  value = azurerm_key_vault.this.vault_uri
}

output "control_plane_workload_identity_client_id" {
  description = "Set as charts/agentplane's controlPlane.serviceAccount.annotations['azure.workload.identity/client-id'] (values-prod.yaml)."
  value       = azurerm_user_assigned_identity.control_plane.client_id
}

output "postgres_fqdn" {
  value = azurerm_postgresql_flexible_server.this.fqdn
}

output "redis_hostname" {
  value = azurerm_redis_cache.this.hostname
}

output "redis_ssl_port" {
  value = azurerm_redis_cache.this.ssl_port
}

output "cosmos_mongo_account_name" {
  value = azurerm_cosmosdb_account.this.name
}

# Deliberately no output for any secret value (postgres password, mongo connection string,
# redis key) - those live in Key Vault ONLY (keyvault.tf) and reading them back out via
# `terraform output` would recreate exactly the "secret in a place other than Key Vault" problem
# this whole module exists to avoid. Use `az keyvault secret show` (or the running pod, via the
# CSI driver) instead - see README.md.
