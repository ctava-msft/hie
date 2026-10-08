resource "azurerm_virtual_network" "workspace" {
  name                = local.names.vnet
  location            = var.location
  resource_group_name = azurerm_resource_group.workspace.name
  address_space       = [var.vnet_cidr]
  tags                = local.tags
}

resource "azurerm_subnet" "workspace" {
  for_each = {
    aks       = cidrsubnet(var.vnet_cidr, 8, 1)
    apim      = cidrsubnet(var.vnet_cidr, 11, 16)
    endpoints = cidrsubnet(var.vnet_cidr, 8, 3)
  }
  name                              = "snet-${each.key}"
  resource_group_name               = azurerm_resource_group.workspace.name
  virtual_network_name              = azurerm_virtual_network.workspace.name
  address_prefixes                  = [each.value]
  private_endpoint_network_policies = each.key == "endpoints" ? "Disabled" : "Enabled"
}

resource "azurerm_network_security_group" "apim" {
  name                = "nsg-${local.stem}-apim"
  location            = var.location
  resource_group_name = azurerm_resource_group.workspace.name
  tags                = local.tags
}

locals {
  apim_rules = {
    management = { priority = 100, direction = "Inbound", source = "ApiManagement", target = "VirtualNetwork", ports = ["3443"], protocol = "Tcp" }
    probe      = { priority = 110, direction = "Inbound", source = "AzureLoadBalancer", target = "VirtualNetwork", ports = ["6390"], protocol = "Tcp" }
    https      = { priority = 120, direction = "Inbound", source = "Internet", target = "VirtualNetwork", ports = ["443"], protocol = "Tcp" }
    backend    = { priority = 200, direction = "Outbound", source = "VirtualNetwork", target = cidrsubnet(var.vnet_cidr, 8, 1), ports = ["80"], protocol = "Tcp" }
    storage    = { priority = 210, direction = "Outbound", source = "VirtualNetwork", target = "Storage", ports = ["443"], protocol = "Tcp" }
    sql        = { priority = 220, direction = "Outbound", source = "VirtualNetwork", target = "Sql", ports = ["1433"], protocol = "Tcp" }
    identity   = { priority = 230, direction = "Outbound", source = "VirtualNetwork", target = "AzureActiveDirectory", ports = ["443"], protocol = "Tcp" }
    vault      = { priority = 240, direction = "Outbound", source = "VirtualNetwork", target = "AzureKeyVault", ports = ["443"], protocol = "Tcp" }
    monitor    = { priority = 250, direction = "Outbound", source = "VirtualNetwork", target = "AzureMonitor", ports = ["443", "1886"], protocol = "Tcp" }
    certs      = { priority = 260, direction = "Outbound", source = "VirtualNetwork", target = "Internet", ports = ["80", "443"], protocol = "Tcp" }
    dns_udp    = { priority = 270, direction = "Outbound", source = "VirtualNetwork", target = "168.63.129.16", ports = ["53"], protocol = "Udp" }
    dns_tcp    = { priority = 280, direction = "Outbound", source = "VirtualNetwork", target = "168.63.129.16", ports = ["53"], protocol = "Tcp" }
  }
}

resource "azurerm_network_security_rule" "apim" {
  for_each                    = local.apim_rules
  name                        = "allow-${each.key}"
  resource_group_name         = azurerm_resource_group.workspace.name
  network_security_group_name = azurerm_network_security_group.apim.name
  priority                    = each.value.priority
  direction                   = each.value.direction
  access                      = "Allow"
  protocol                    = each.value.protocol
  source_port_range           = "*"
  destination_port_ranges     = each.value.ports
  source_address_prefix       = each.value.source
  destination_address_prefix  = each.value.target
}

resource "azurerm_subnet_network_security_group_association" "apim" {
  subnet_id                 = azurerm_subnet.workspace["apim"].id
  network_security_group_id = azurerm_network_security_group.apim.id
}

resource "azurerm_public_ip" "gateway" {
  name                = "pip-${local.stem}-gateway"
  resource_group_name = azurerm_resource_group.workspace.name
  location            = var.location
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = local.names.apim
  tags                = local.tags
  lifecycle {
    # Preserve required IP tags injected by an organization's Azure policy.
    ignore_changes = [ip_tags]
  }
}

resource "azurerm_private_dns_zone" "data" {
  for_each = {
    acr = "privatelink.azurecr.io"
  }
  name                = each.value
  resource_group_name = azurerm_resource_group.workspace.name
  tags                = local.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "data" {
  for_each              = azurerm_private_dns_zone.data
  name                  = "link-${local.stem}-${each.key}"
  resource_group_name   = azurerm_resource_group.workspace.name
  private_dns_zone_name = each.value.name
  virtual_network_id    = azurerm_virtual_network.workspace.id
  registration_enabled  = false
  tags                  = local.tags
}

resource "azurerm_private_endpoint" "data" {
  for_each = {
    acr = { id = azurerm_container_registry.workspace.id, group = "registry" }
  }
  name                = "pep-${local.stem}-${each.key}"
  location            = var.location
  resource_group_name = azurerm_resource_group.workspace.name
  subnet_id           = azurerm_subnet.workspace["endpoints"].id
  tags                = local.tags
  private_service_connection {
    name                           = each.key
    private_connection_resource_id = each.value.id
    subresource_names              = [each.value.group]
    is_manual_connection           = false
  }
  private_dns_zone_group {
    name                 = each.key
    private_dns_zone_ids = [azurerm_private_dns_zone.data[each.key].id]
  }
}
