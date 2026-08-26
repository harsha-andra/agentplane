resource "azurerm_redis_cache" "this" {
  name                = "redis-${local.name_prefix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name

  capacity = var.redis_capacity
  family   = var.redis_family
  sku_name = var.redis_sku_name

  # TLS only - matches agentplane.stream's use of StringRedisTemplate over a plain TCP
  # connection today only in local/docker-compose (redis:7-alpine, no TLS); in Azure, the
  # control plane's SPRING_DATA_REDIS_* config (see charts/agentplane/values.yaml's
  # `datastores.redis`) must point at port 6380 with TLS enabled, not the non-TLS port.
  non_ssl_port_enabled = false
  minimum_tls_version   = "1.2"

  # No public endpoint - reachable only via the private endpoint below.
  public_network_access_enabled = false

  redis_configuration {}

  tags = local.default_tags
}

resource "azurerm_private_endpoint" "redis" {
  name                = "pe-redis-${local.name_prefix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  subnet_id           = azurerm_subnet.private_endpoints.id

  private_service_connection {
    name                           = "redis-privateserviceconnection"
    private_connection_resource_id = azurerm_redis_cache.this.id
    subresource_names              = ["redisCache"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "redis-dns-zone-group"
    private_dns_zone_ids = [azurerm_private_dns_zone.redis.id]
  }

  tags = local.default_tags
}
