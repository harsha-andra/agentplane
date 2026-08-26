# Copy this file to something like dev.tfvars (already gitignored - see .gitignore's
# `*.tfvars` / `!example.tfvars` rule) and adjust for your subscription. Nothing below is a
# secret; subscription_id/tenant_id are deliberately not variables at all - see providers.tf and
# README.md, "Authentication".

project     = "agentplane"
environment = "dev"
location    = "eastus2"

tags = {
  owner = "harsha.andra111@gmail.com"
}

vnet_address_space           = "10.42.0.0/16"
aks_subnet_cidr               = "10.42.0.0/20"
postgres_subnet_cidr          = "10.42.16.0/24"
private_endpoint_subnet_cidr  = "10.42.17.0/24"

aks_kubernetes_version = "1.29"
aks_node_count          = 3
aks_node_vm_size        = "Standard_D4s_v5"

postgres_sku_name       = "B_Standard_B2s"
postgres_storage_mb     = 32768
postgres_version        = "16"
postgres_admin_username = "agentplane_admin"

redis_sku_name = "Standard"
redis_family   = "C"
redis_capacity = 1

cosmos_consistency_level = "Session"

key_vault_sku = "standard"

# Must match the Helm release: `helm upgrade --install agentplane charts/agentplane -n agentplane`
# produces a ServiceAccount named "agentplane-control-plane" (agentplane.serviceAccountName in
# charts/agentplane/templates/_helpers.tpl) in namespace "agentplane" - keep these two in sync.
control_plane_namespace             = "agentplane"
control_plane_service_account_name  = "agentplane-control-plane"
