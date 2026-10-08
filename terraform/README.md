# HAPI FHIR Azure hosting

**Repurposed FHIR-only root. Use a fresh state.** The former Python workspace,
SQL, Cosmos, SPA and Agent 365 configuration has been removed. Applying this
configuration against its old state can schedule deletion of those resources.
Read [ADOPTION.md](ADOPTION.md) before any existing-resource reuse.

## Included

- Public HTTPS APIM `/fhir` API, GET only, single-tenant Entra JWT validation,
  required `Fhir.Read` application role, IP-based throttling and no-store responses.
- Private AKS API, Entra RBAC, OIDC/workload identity and two nodes by default.
- Private Premium ACR, administrator/anonymous access disabled, kubelet `AcrPull`.
- Three UAMIs: control plane, kubelet and HAPI runtime.
- VNet, APIM network rules, private ACR endpoint and private DNS.
- API application/service principal and explicit app-role assignments to approved clients.
- A nonsecret `fhir_runtime` output for the [AKS deployment script](../scripts/deploy-hapi.ps1).

There is no SQL/Cosmos/JPA database or persistent clinical volume. Fabric remains
the Gold system of record. The existing [Bicep entry point](../infra/main.bicep)
owns Fabric workspace/Eventhouse/database/Eventstream provisioning; Terraform
consumes the actual `gold_query_uri` and `gold_database_name`.

## Permissions

| Principal | Permission |
|---|---|
| AKS control identity | VNet Network Contributor; kubelet Managed Identity Operator |
| Kubelet identity | AcrPull on this registry |
| Operator | Cluster User + AKS RBAC Cluster Admin; AcrPush; approved Entra deployment rights |
| HAPI runtime identity | Separate native Gold database `viewers` grant, not Azure ARM data access |
| Approved client service principals | `Fhir.Read` app role on this API; no direct Gold permission |

`fhir_reader_principal_ids` takes **service-principal object IDs**. Empty means
no clients are authorized. Clients obtain an app-only token for the
`api_token_scope` output; the resulting v2 JWT audience is the API client ID.
This does not implement SMART on FHIR, delegated user scopes or patient-level authorization.

## Deploy

Follow the full [FHIR/ePNA runbook](../docs/fhir-gold-poc.md), including Fabric
setup, native viewer permission, image build/push and Kubernetes deployment.

Before applying:

1. Register `Microsoft.Network`, `Microsoft.ContainerService`,
   `Microsoft.ContainerRegistry`, `Microsoft.ManagedIdentity` and `Microsoft.ApiManagement`.
2. Authorize the deployer for Azure resource/role-assignment creation and the
   Entra app/service-principal/app-role operations. Do not weaken authentication on a denial.
3. Approve regional VM quotas, APIM SKU and distinct VNet/pod/service `/16` ranges.
4. Provide a runner with private network and DNS reachability to AKS and ACR.
   This is not an air-gapped design: approved Entra, Fabric and image egress is required.
5. Configure encrypted, locked, separate Terraform backend state before the first apply.
6. Copy [terraform.tfvars.example](terraform.tfvars.example) to an ignored environment file.

From this directory:

```powershell
terraform init
terraform fmt -check -recursive
terraform validate
terraform test
terraform plan -var-file=dev.tfvars -out=deployment.tfplan
# Apply only after approving costs, identities, networking and the saved plan.
terraform apply deployment.tfplan
terraform output -json fhir_runtime > ..\hie.runtime.json
terraform output -raw gold_reader_grant_kql
```

Run the emitted KQL grant as an administrator **in the named Gold database**.
Resource Owner/Contributor does not grant Kusto query access. The runtime never
needs a database administrator, ingestor or Fabric workspace Contributor role.

Developer APIM has no production SLA. AKS nodes/disks, APIM, Premium ACR, private
endpoints, deployment-script support resources and Fabric capacity are billable.
The private APIM-to-pod hop uses HTTP for synthetic POC traffic; see the runbook's
production gates before using any PHI.

Resource-group/AKS destroy guards remain. No cloud deployment or existing-state
migration is implied by local validation. Keep state, plans, real environment
files, runtime outputs, kubeconfigs, tokens and connection strings out of source.
