locals {
  suffix = var.suffix == null ? substr(sha256("${var.subscription_id}:${var.prefix}:${var.environment}"), 0, 8) : var.suffix
  stem   = "${var.prefix}-${var.environment}-${local.suffix}"
  names = merge({
    group            = "rg-${local.stem}"
    vnet             = "vnet-${local.stem}"
    aks              = "aks-${local.stem}"
    node_group       = "rg-${local.stem}-nodes"
    acr              = "acr${var.prefix}${var.environment}${local.suffix}"
    apim             = "apim-${local.stem}"
    identity_control = "id-${local.stem}-control"
    identity_kubelet = "id-${local.stem}-kubelet"
    identity_fhir    = "id-${local.stem}-fhir"
  }, var.resource_names)
  tags              = merge(var.tags, { project = var.prefix, environment = var.environment, managed_by = "terraform", workload = "hapi-fhir" })
  fhir_private_ip   = cidrhost(cidrsubnet(var.vnet_cidr, 8, 1), 11)
  fhir_read_role_id = uuidv5("url", "urn:${var.subscription_id}:${local.stem}:Fhir.Read")
}

resource "azurerm_resource_group" "workspace" {
  name     = local.names.group
  location = var.location
  tags     = local.tags
  lifecycle {
    prevent_destroy = true
    precondition {
      condition = length(toset([
        cidrhost(var.vnet_cidr, 0), cidrhost(var.pod_cidr, 0), cidrhost(var.service_cidr, 0)
      ])) == 3
      error_message = "VNet, pod and service /16 networks must be distinct and non-overlapping."
    }
  }
}

resource "azurerm_user_assigned_identity" "workload" {
  for_each            = toset(["control", "kubelet", "fhir"])
  name                = local.names["identity_${each.key}"]
  resource_group_name = azurerm_resource_group.workspace.name
  location            = var.location
  tags                = local.tags
}

resource "azurerm_role_assignment" "network" {
  scope                = azurerm_virtual_network.workspace.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.workload["control"].principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "identity_operator" {
  scope                = azurerm_user_assigned_identity.workload["kubelet"].id
  role_definition_name = "Managed Identity Operator"
  principal_id         = azurerm_user_assigned_identity.workload["control"].principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_container_registry" "workspace" {
  name                          = local.names.acr
  resource_group_name           = azurerm_resource_group.workspace.name
  location                      = var.location
  sku                           = "Premium"
  admin_enabled                 = false
  anonymous_pull_enabled        = false
  public_network_access_enabled = false
  role_assignment_mode          = "LegacyRegistryPermissions"
  tags                          = local.tags
}

resource "azurerm_role_assignment" "image_pull" {
  scope                = azurerm_container_registry.workspace.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.workload["kubelet"].principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "image_push" {
  scope                = azurerm_container_registry.workspace.id
  role_definition_name = "AcrPush"
  principal_id         = var.operator_object_id
}

resource "azurerm_kubernetes_cluster" "workspace" {
  name                                = local.names.aks
  resource_group_name                 = azurerm_resource_group.workspace.name
  location                            = var.location
  node_resource_group                 = local.names.node_group
  dns_prefix                          = local.names.aks
  sku_tier                            = "Standard"
  private_cluster_enabled             = true
  private_cluster_public_fqdn_enabled = false
  private_dns_zone_id                 = "System"
  local_account_disabled              = true
  role_based_access_control_enabled   = true
  oidc_issuer_enabled                 = true
  workload_identity_enabled           = true
  tags                                = local.tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.workload["control"].id]
  }
  kubelet_identity {
    client_id                 = azurerm_user_assigned_identity.workload["kubelet"].client_id
    object_id                 = azurerm_user_assigned_identity.workload["kubelet"].principal_id
    user_assigned_identity_id = azurerm_user_assigned_identity.workload["kubelet"].id
  }
  azure_active_directory_role_based_access_control {
    tenant_id          = var.tenant_id
    azure_rbac_enabled = true
  }
  default_node_pool {
    name                         = "system"
    temporary_name_for_rotation  = "systemtmp"
    vm_size                      = var.aks_vm_size
    node_count                   = var.aks_node_count
    vnet_subnet_id               = azurerm_subnet.workspace["aks"].id
    os_sku                       = "Ubuntu"
    os_disk_size_gb              = 64
    max_pods                     = 30
    only_critical_addons_enabled = false
    upgrade_settings {
      max_surge                     = "10%"
      drain_timeout_in_minutes      = 0
      node_soak_duration_in_minutes = 0
    }
  }
  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    pod_cidr            = var.pod_cidr
    service_cidr        = var.service_cidr
    dns_service_ip      = cidrhost(var.service_cidr, 10)
    load_balancer_sku   = "standard"
    outbound_type       = "loadBalancer"
  }
  lifecycle {
    prevent_destroy = true
    # Tenant policy owns Defender and its workspace.
    ignore_changes = [microsoft_defender]
  }
  depends_on = [azurerm_role_assignment.network, azurerm_role_assignment.identity_operator, azurerm_role_assignment.image_pull]
}

resource "azurerm_role_assignment" "cluster_operator" {
  for_each             = toset(["Azure Kubernetes Service Cluster User Role", "Azure Kubernetes Service RBAC Cluster Admin"])
  scope                = azurerm_kubernetes_cluster.workspace.id
  role_definition_name = each.key
  principal_id         = var.operator_object_id
}

resource "azurerm_federated_identity_credential" "fhir" {
  name                      = "hapi-fhir"
  user_assigned_identity_id = azurerm_user_assigned_identity.workload["fhir"].id
  audience                  = ["api://AzureADTokenExchange"]
  issuer                    = azurerm_kubernetes_cluster.workspace.oidc_issuer_url
  subject                   = "system:serviceaccount:fhir:hapi-fhir"
}
