variable "subscription_id" {
  type        = string
  description = "Explicit target subscription; never inferred from the CLI default."
  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$", var.subscription_id))
    error_message = "subscription_id must be a UUID."
  }
}

variable "tenant_id" {
  type        = string
  description = "Entra tenant for all hosting and application identities."
  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$", var.tenant_id))
    error_message = "tenant_id must be a UUID."
  }
}

variable "operator_object_id" {
  type        = string
  description = "Deployer/owner object ID in this tenant; receives scoped AKS admin and AcrPush."
  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$", var.operator_object_id))
    error_message = "operator_object_id must be the tenant object UUID, not a client ID."
  }
}

variable "prefix" {
  type        = string
  description = "Lowercase project prefix."
  default     = "hie"
  validation {
    condition     = can(regex("^[a-z][a-z0-9]{2,11}$", var.prefix))
    error_message = "prefix must be 3-12 lowercase alphanumeric characters, starting with a letter."
  }
}

variable "environment" {
  type        = string
  default     = "dev"
  description = "Environment name; use a separate state per environment."
  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,7}$", var.environment))
    error_message = "environment must be 2-8 lowercase alphanumeric characters."
  }
}

variable "suffix" {
  type        = string
  default     = null
  description = "Optional 4-8 character global-name suffix; null derives it from subscription/project/environment."
  validation {
    condition     = var.suffix == null ? true : can(regex("^[a-z0-9]{4,8}$", var.suffix))
    error_message = "suffix must be null or 4-8 lowercase alphanumeric characters."
  }
}

variable "location" {
  type        = string
  default     = "eastus2"
  description = "Hosting region. Verify subscription SKU/capacity availability before applying."
}

variable "publisher_name" {
  type        = string
  default     = "HIE FHIR POC"
  description = "APIM publisher display name."
}

variable "publisher_email" {
  type        = string
  description = "Real operational contact required by APIM."
  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.publisher_email))
    error_message = "publisher_email must be a valid contact email."
  }
}

variable "vnet_cidr" {
  type        = string
  default     = "10.230.0.0/16"
  description = "Approved non-overlapping IPv4 /16 for nodes, APIM and private endpoints."
  validation {
    condition     = can(cidrnetmask(var.vnet_cidr)) && endswith(var.vnet_cidr, "/16")
    error_message = "vnet_cidr must be an IPv4 /16."
  }
}

variable "pod_cidr" {
  type        = string
  default     = "10.244.0.0/16"
  description = "Overlay pod network; must not overlap VNet or service networks."
  validation {
    condition     = can(cidrnetmask(var.pod_cidr)) && endswith(var.pod_cidr, "/16")
    error_message = "pod_cidr must be an IPv4 /16."
  }
}

variable "service_cidr" {
  type        = string
  default     = "10.240.0.0/16"
  description = "Kubernetes service network; must not overlap VNet or pods."
  validation {
    condition     = can(cidrnetmask(var.service_cidr)) && endswith(var.service_cidr, "/16")
    error_message = "service_cidr must be an IPv4 /16."
  }
}

variable "aks_vm_size" {
  type        = string
  default     = "Standard_D2ds_v5"
  description = "Available x64 system-pool VM size, at least 2 vCPU/4 GiB."
}

variable "aks_node_count" {
  type        = number
  default     = 2
  description = "System-pool capacity for the stateless HAPI FHIR deployment."
  validation {
    condition     = var.aks_node_count >= 1 && var.aks_node_count <= 10 && floor(var.aks_node_count) == var.aks_node_count
    error_message = "aks_node_count must be an integer between 1 and 10."
  }
}

variable "apim_sku" {
  type        = string
  default     = "Developer_1"
  description = "Classic VNet-injected APIM tier. Developer is nonproduction/no SLA; choose Premium for production."
  validation {
    condition     = contains(["Developer_1", "Premium_1", "Premium_2"], var.apim_sku)
    error_message = "Use a supported classic VNet-injected Developer or Premium SKU."
  }
}

variable "gold_database_name" {
  type        = string
  default     = "hie_gold"
  description = "Existing Fabric Gold KQL database name. This root does not create another clinical database."
  validation {
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$", var.gold_database_name))
    error_message = "gold_database_name must be a safe 1-128 character name or database UUID."
  }
}

variable "gold_query_uri" {
  type        = string
  description = "HTTPS query URI copied from the Gold Eventhouse, not the ingest URI or Fabric REST API."
  validation {
    condition     = can(regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.kusto\\.fabric\\.microsoft\\.com$", var.gold_query_uri))
    error_message = "Use the public-cloud Fabric Eventhouse HTTPS query URI, without a path or trailing slash."
  }
}

variable "fhir_reader_principal_ids" {
  type        = set(string)
  default     = []
  description = "Approved client service-principal object IDs granted the Fhir.Read app role. Empty means no clients are authorized."
  validation {
    condition     = alltrue([for id in var.fhir_reader_principal_ids : can(regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$", id))])
    error_message = "Reader IDs must be service-principal object UUIDs, not application/client IDs."
  }
}

variable "resource_names" {
  type        = map(string)
  default     = {}
  description = "Reviewed adoption-only name overrides for Azure hosting and the three managed identities."
  validation {
    condition = alltrue([for key in keys(var.resource_names) : contains([
      "group", "vnet", "aks", "node_group", "acr", "apim",
      "identity_control", "identity_kubelet", "identity_fhir"
    ], key)])
    error_message = "resource_names contains an unsupported override."
  }
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Additional organizational tags."
}
