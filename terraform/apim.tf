resource "azurerm_api_management" "workspace" {
  name                          = local.names.apim
  resource_group_name           = azurerm_resource_group.workspace.name
  location                      = var.location
  publisher_name                = var.publisher_name
  publisher_email               = var.publisher_email
  sku_name                      = var.apim_sku
  virtual_network_type          = "External"
  public_network_access_enabled = true
  public_ip_address_id          = azurerm_public_ip.gateway.id
  tags                          = local.tags
  virtual_network_configuration {
    subnet_id = azurerm_subnet.workspace["apim"].id
  }
  security {
    backend_ssl30_enabled  = false
    backend_tls10_enabled  = false
    backend_tls11_enabled  = false
    frontend_ssl30_enabled = false
    frontend_tls10_enabled = false
    frontend_tls11_enabled = false
  }
  timeouts {
    create = "120m"
    update = "120m"
  }
  depends_on = [azurerm_subnet_network_security_group_association.apim, azurerm_network_security_rule.apim]
}

resource "azurerm_api_management_api" "fhir" {
  name                  = "gold-fhir"
  resource_group_name   = azurerm_resource_group.workspace.name
  api_management_name   = azurerm_api_management.workspace.name
  revision              = "1"
  display_name          = "Gold Eventhouse FHIR R4 (read-only POC)"
  path                  = "fhir"
  protocols             = ["https"]
  service_url           = "http://${local.fhir_private_ip}:80/fhir"
  subscription_required = false
}

resource "azurerm_api_management_api_operation" "fhir" {
  for_each = {
    root = { method = "GET", path = "/" }
    read = { method = "GET", path = "/*" }
  }
  operation_id        = each.key
  api_name            = azurerm_api_management_api.fhir.name
  api_management_name = azurerm_api_management.workspace.name
  resource_group_name = azurerm_resource_group.workspace.name
  display_name        = "FHIR ${each.key}"
  method              = each.value.method
  url_template        = each.value.path
}

resource "azurerm_api_management_api_policy" "fhir" {
  api_name            = azurerm_api_management_api.fhir.name
  api_management_name = azurerm_api_management.workspace.name
  resource_group_name = azurerm_resource_group.workspace.name
  xml_content = templatefile("${path.module}/policies/fhir.xml.tftpl", {
    tenant_id     = var.tenant_id
    api_client_id = azuread_application.api.client_id
  })
}
