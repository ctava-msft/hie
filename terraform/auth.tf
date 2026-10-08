resource "azuread_application" "api" {
  display_name            = "${local.stem}-fhir-api"
  sign_in_audience        = "AzureADMyOrg"
  owners                  = [var.operator_object_id]
  prevent_duplicate_names = true
  api {
    requested_access_token_version = 2
  }
  app_role {
    id                   = local.fhir_read_role_id
    allowed_member_types = ["Application"]
    description          = "Read the approved FHIR R4 resource and search surface over Gold Eventhouse."
    display_name         = "Read Gold FHIR"
    enabled              = true
    value                = "Fhir.Read"
  }
  lifecycle {
    ignore_changes = [identifier_uris]
  }
}

resource "azuread_application_identifier_uri" "api" {
  application_id = azuread_application.api.id
  identifier_uri = "api://${azuread_application.api.client_id}"
}

resource "azuread_service_principal" "api" {
  client_id                    = azuread_application.api.client_id
  owners                       = [var.operator_object_id]
  app_role_assignment_required = true
}

resource "azuread_app_role_assignment" "fhir_reader" {
  for_each            = var.fhir_reader_principal_ids
  app_role_id         = local.fhir_read_role_id
  principal_object_id = each.value
  resource_object_id  = azuread_service_principal.api.object_id
}
