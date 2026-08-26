# subscription_id/tenant_id are deliberately NOT set here - the azurerm provider picks them up
# from the environment (ARM_SUBSCRIPTION_ID / ARM_TENANT_ID) or from the active `az login`
# session (`az account show`) instead. Hardcoding either into version-controlled .tf would tie
# this configuration to one specific Azure tenant/subscription and risk a real identifier
# ending up in git history - see README.md, "Authentication", for the exact commands.
provider "azurerm" {
  features {
    key_vault {
      # Soft-delete is on by default for Key Vault and cannot be disabled - purge_on_destroy
      # only controls what `terraform destroy` does with an already-soft-deleted vault. Off by
      # default here: an accidental `destroy` should not also purge the one thing (Key Vault)
      # holding every generated credential.
      purge_soft_delete_on_destroy = false
    }
    resource_group {
      # Prevents `terraform destroy` from being blocked by resources this configuration didn't
      # create itself ending up in the same resource group; does not weaken anything else.
      prevent_deletion_if_contains_resources = false
    }
  }
}

provider "random" {}
