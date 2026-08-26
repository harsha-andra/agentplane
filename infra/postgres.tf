resource "random_password" "postgres_admin" {
  length      = 32
  special     = true
  min_lower   = 1
  min_upper   = 1
  min_numeric = 1
  # Postgres Flexible Server rejects a handful of characters in the admin password; restrict
  # the special-character set generated to ones it actually accepts.
  override_special = "!#$%&*()-_=+[]{}"
}

resource "azurerm_postgresql_flexible_server" "this" {
  name                = "psql-${local.name_prefix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name

  version  = var.postgres_version
  sku_name = var.postgres_sku_name
  storage_mb = var.postgres_storage_mb

  administrator_login    = var.postgres_admin_username
  administrator_password = random_password.postgres_admin.result

  # VNet-integrated (not a Private Endpoint - Flexible Server's own integration model): the
  # server's network interface lives directly in the delegated subnet, reachable only from
  # inside the VNet (or peered/VPN'd networks) - never from the public internet.
  delegated_subnet_id = azurerm_subnet.postgres.id
  private_dns_zone_id = azurerm_private_dns_zone.postgres.id

  zone = "1"

  tags = local.default_tags

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]

  lifecycle {
    # The generated password is the source of truth in Key Vault (keyvault.tf); Azure also lets
    # an operator rotate it out-of-band (az postgres flexible-server update) without Terraform
    # fighting to set it back on every apply.
    ignore_changes = [administrator_password, zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "agentplane" {
  name      = "agentplane"
  server_id = azurerm_postgresql_flexible_server.this.id
  # Matches control-plane's Flyway migrations, developed/tested against Postgres 16's own
  # defaults (see control-plane/docker-compose.yml).
  charset   = "UTF8"
  collation = "en_US.utf8"
}
