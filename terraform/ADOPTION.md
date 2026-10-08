# Existing-resource adoption: FHIR state boundary

**Use a fresh state and fresh environment by default.** This root has been
repurposed from a Python/SQL/Cosmos workspace to the Gold HAPI FHIR POC.
It is not a safe in-place upgrade of the old state. Configuration removal can
schedule deletion of SQL, Cosmos, old identities, SPA registrations and APIs,
even if their former configuration had destroy guards.

## Before reusing infrastructure

1. Freeze old-root applies. Securely retain the original configuration, state
   and data backups. State may contain secrets.
2. Inventory real Azure resource IDs, SKUs, network/private-DNS settings,
   identities, OIDC issuers, federated subjects and API registrations.
3. Leave unrelated databases/apps under their original owner. Their retirement
   is a separate, explicitly approved operation; this POC does not migrate data.
4. Match reviewed `resource_names`, network ranges, region and VM/SKU inputs.
   A public cluster or Basic registry is not equivalent to this private topology.
5. Create a separate FHIR API registration and runtime identity unless an
   identity migration has been explicitly designed. Do not repurpose an active
   browser API's audience/roles without coordinating its clients.

## Hosting addresses

| Resource | Root address |
|---|---|
| Resource group | `azurerm_resource_group.workspace` |
| VNet and subnets | `azurerm_virtual_network.workspace`, `azurerm_subnet.workspace[...]` |
| Private AKS | `azurerm_kubernetes_cluster.workspace` |
| Private ACR | `azurerm_container_registry.workspace` |
| ACR private endpoint/DNS | `azurerm_private_endpoint.data["acr"]`, `azurerm_private_dns_zone.data["acr"]` |
| APIM service | `azurerm_api_management.workspace` |
| FHIR API/policy | `azurerm_api_management_api.fhir`, `azurerm_api_management_api_policy.fhir` |
| Managed identities | `azurerm_user_assigned_identity.workload["control"|"kubelet"|"fhir"]` |
| Runtime federation | `azurerm_federated_identity_credential.fhir` |
| API registration/principal | `azuread_application.api`, `azuread_service_principal.api` |

Import IDs differ by resource/provider. Application object IDs are not client
IDs. Inventory dependent role assignments, API operations, DNS links and
identity bindings as well as the top-level resources.

Under a reviewed maintenance plan, transfer only selected addresses from the
old state **without destroying their Azure objects**, then import into the new
owner state. Never run two roots against the same objects. Do not use blanket
state removal, automatic imports, destroy or a copied old state as a shortcut.

The FHIR federated subject is `system:serviceaccount:fhir:hapi-fhir`.
Changing a cluster/OIDC issuer or replacing identities can break running pods.
Stage new bindings and the Gold database viewer grant before switching traffic.
Fabric item/data permissions are not imported as Azure ARM role assignments.

Require a plan with no unapproved replacements, deletions or permission
expansions. Validate private DNS, ACR push/pull, workload identity, Gold reads,
authenticated APIM access and ePNA latest-state behavior before cutover.
Keep a rollback path; this guide does not perform adoption automatically.
