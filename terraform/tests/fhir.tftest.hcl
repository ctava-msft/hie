mock_provider "azurerm" {}
mock_provider "azuread" {}

variables {
  subscription_id    = "11111111-1111-1111-1111-111111111111"
  tenant_id          = "22222222-2222-2222-2222-222222222222"
  operator_object_id = "33333333-3333-3333-3333-333333333333"
  publisher_email    = "poc@example.org"
  gold_query_uri     = "https://test.z1.kusto.fabric.microsoft.com"
}

run "private_read_only_fhir" {
  command = plan

  assert {
    condition     = azurerm_kubernetes_cluster.workspace.private_cluster_enabled && azurerm_kubernetes_cluster.workspace.local_account_disabled && azurerm_kubernetes_cluster.workspace.workload_identity_enabled
    error_message = "AKS must retain a private API, Entra RBAC and workload identity."
  }
  assert {
    condition     = !azurerm_container_registry.workspace.admin_enabled && !azurerm_container_registry.workspace.public_network_access_enabled && azurerm_container_registry.workspace.sku == "Premium"
    error_message = "ACR must be private with administrator credentials disabled."
  }
  assert {
    condition     = length(azurerm_user_assigned_identity.workload) == 3 && azurerm_federated_identity_credential.fhir.subject == "system:serviceaccount:fhir:hapi-fhir"
    error_message = "Only control, kubelet and FHIR identities belong in this stack."
  }
  assert {
    condition     = azurerm_api_management_api.fhir.path == "fhir" && alltrue([for operation in azurerm_api_management_api_operation.fhir : operation.method == "GET"])
    error_message = "APIM must expose only the read-only /fhir route."
  }
  assert {
    condition     = azuread_service_principal.api.app_role_assignment_required && length(azuread_app_role_assignment.fhir_reader) == 0
    error_message = "An empty reader list must not authorize clients implicitly."
  }
  assert {
    condition     = length(azurerm_private_endpoint.data) == 1
    error_message = "No SQL or Cosmos data store may be created for the virtualization service."
  }
}

run "reject_non_fabric_endpoint" {
  command = plan
  variables {
    gold_query_uri = "https://example.org"
  }
  expect_failures = [var.gold_query_uri]
}

run "reject_overlapping_networks" {
  command = plan
  variables {
    pod_cidr = "10.230.0.0/16"
  }
  expect_failures = [azurerm_resource_group.workspace]
}
