output "fhir_runtime" {
  description = "Nonsecret deployment contract consumed by scripts/deploy-hapi.ps1."
  value = {
    deployment_profile         = "gold-hapi-fhir-r4"
    subscription_id            = var.subscription_id
    tenant_id                  = var.tenant_id
    resource_group_name        = azurerm_resource_group.workspace.name
    aks_cluster_name           = azurerm_kubernetes_cluster.workspace.name
    acr_name                   = azurerm_container_registry.workspace.name
    acr_login_server           = azurerm_container_registry.workspace.login_server
    fhir_base_url              = "${azurerm_api_management.workspace.gateway_url}/fhir"
    fhir_private_ip            = local.fhir_private_ip
    apim_subnet_cidr           = azurerm_subnet.workspace["apim"].address_prefixes[0]
    aks_subnet_name            = azurerm_subnet.workspace["aks"].name
    fhir_identity_client_id    = azurerm_user_assigned_identity.workload["fhir"].client_id
    fhir_identity_principal_id = azurerm_user_assigned_identity.workload["fhir"].principal_id
    gold_query_uri             = var.gold_query_uri
    gold_database_name         = var.gold_database_name
    api_client_id              = azuread_application.api.client_id
    api_token_scope            = "${azuread_application_identifier_uri.api.identifier_uri}/.default"
  }
}

output "gold_reader_grant_kql" {
  description = "Run once as a Gold database administrator. ARM RBAC does not grant Fabric/Kusto data access."
  value       = ".add database ['${var.gold_database_name}'] viewers ('aadapp=${azurerm_user_assigned_identity.workload["fhir"].client_id};${var.tenant_id}') 'HAPI FHIR read-only workload'"
}

output "resource_names" {
  value       = local.names
  description = "Resolved names for review before apply/import."
}
