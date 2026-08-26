terraform {
  required_version = ">= 1.7.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.116"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # No backend block on purpose: which backend (azurerm storage account, Terraform Cloud, ...)
  # is an environment-specific decision, and hardcoding one here would silently point every
  # environment at the same state. Configure a backend explicitly per environment instead:
  #
  #   terraform init -backend-config="resource_group_name=..." \
  #                   -backend-config="storage_account_name=..." \
  #                   -backend-config="container_name=tfstate" \
  #                   -backend-config="key=agentplane-<environment>.tfstate"
  #
  # with a `backend "azurerm" {}` block (all fields left blank, filled by -backend-config) added
  # here once that storage account exists. Local state (the default with no backend block) is
  # fine for a single operator trying this out, and is NEVER committed - see .gitignore.
}
