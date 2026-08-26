locals {
  name_prefix = "${var.project}-${var.environment}"

  default_tags = merge({
    project    = var.project
    environment = var.environment
    managed-by = "terraform"
  }, var.tags)

  # Azure requires globally-unique names for ACR, Key Vault and Cosmos DB accounts, and disallows
  # hyphens in some of them (ACR, storage) - random_string.suffix (see main.tf) keeps this
  # configuration reusable across multiple deployments (e.g. two people trying this out, or a
  # second environment) without a name collision, without the operator having to hand-pick
  # unique names themselves.
  unique_suffix = random_string.suffix.result
}
