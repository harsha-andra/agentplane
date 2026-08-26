# Cosmos DB's Mongo API - stands in for a self-hosted MongoDB replica set (control-plane/
# docker-compose.yml uses mongo:7 directly for local dev) so trace.RunTrace and its aggregation
# pipeline (TraceAnalyticsService) run against a managed, HA-by-default backend in Azure without
# any application code change - the Mongo wire protocol is what control-plane/src actually
# speaks, not anything Cosmos-specific.
resource "azurerm_cosmosdb_account" "this" {
  name                = "cosmos-${local.name_prefix}-${local.unique_suffix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name

  offer_type = "Standard"
  kind       = "MongoDB"

  # 4.2 supports the aggregation operators TraceAnalyticsService actually uses ($match, $group,
  # $project, $sort, $unwind, $push, $cond via ConditionalOperators) - see that class's Javadoc
  # for exactly which pipeline stages it relies on. $percentile (7+) is deliberately NOT
  # required - the class computes p95 in application code precisely so it isn't tied to a
  # specific MongoDB (or Cosmos API) version - see docs/ARCHITECTURE.md.
  capabilities {
    name = "EnableMongo"
  }
  # Serverless: the control plane's trace volume is spiky (bursts of tool-call writes while
  # runs are active, near-idle otherwise) rather than a steady load worth provisioning fixed
  # RU/s for - serverless also means azurerm_cosmosdb_mongo_database/_collection below need no
  # throughput/autoscale_settings block at all. Trade-off: serverless caps a single account at
  # 20,000 RU/s peak and cannot be combined with multi-region writes - fine for this reference
  # architecture, worth revisiting for a genuinely high-traffic deployment.
  capabilities {
    name = "EnableServerless"
  }
  mongo_server_version = "4.2"

  consistency_policy {
    consistency_level = var.cosmos_consistency_level
  }

  geo_location {
    location          = azurerm_resource_group.this.location
    failover_priority = 0
  }

  public_network_access_enabled    = false
  is_virtual_network_filter_enabled = true

  tags = local.default_tags
}

resource "azurerm_cosmosdb_mongo_database" "agentplane" {
  name                = "agentplane"
  resource_group_name = azurerm_resource_group.this.name
  account_name        = azurerm_cosmosdb_account.this.name
}

resource "azurerm_private_endpoint" "cosmos_mongo" {
  name                = "pe-cosmos-mongo-${local.name_prefix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  subnet_id           = azurerm_subnet.private_endpoints.id

  private_service_connection {
    name                           = "cosmos-mongo-privateserviceconnection"
    private_connection_resource_id = azurerm_cosmosdb_account.this.id
    subresource_names              = ["MongoDB"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "cosmos-mongo-dns-zone-group"
    private_dns_zone_ids = [azurerm_private_dns_zone.cosmos_mongo.id]
  }

  tags = local.default_tags
}
