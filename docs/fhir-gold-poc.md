# HAPI FHIR over Gold Eventhouse: ePNA POC

## Architecture and scope

```text
Synthetic ePNA R4 collection Bundles
  -> one JSON envelope per resource/monitoring event
  -> Fabric Eventstream custom endpoint (Event Hubs protocol)
  -> Gold Eventhouse: GoldFhirEvent
  -> transactional update policies
       -> GoldFhirResource + typed clinical projections
       -> EpnaMonitoringSignal
       -> GoldFhirRejectedEvent for invalid envelopes

Approved consumer -> HTTPS APIM /fhir -> internal AKS HAPI R4 -> parameterized KQL
                                           ^
                                   digest-pinned ACR image
```

This implements **Option 1: direct virtualization**, not the HAPI JPA starter.
[The Java service](../services/hapi-fhir) uses HAPI FHIR 8.12.1, Java 21 and a
plain `RestfulServer` with custom resource providers. Gold's canonical R4 JSON
is returned as FHIR resources. No SQL, Cosmos, PostgreSQL, JPA repository,
clinical persistent volume or second Gold database is created.

[Terraform](../terraform/README.md) provisions Azure hosting and identities.
The existing [Fabric Bicep deployment](../infra/main.bicep) creates/reconciles
the workspace, Eventhouse, KQL database, schema, Gold Eventstream and disabled
Activator rule. Fabric items are not represented as fictitious Azure ARM
Eventhouse/Eventstream resources.

The synthetic ingress deliberately starts at Gold. It does not replace or
claim to implement production Epic ingestion, Bronze/Silver normalization,
HL7 parsing, terminology translation or USCDI+/US Core certification.
Source message-type labels are illustrative; absent Bronze/Silver IDs and HL7
versions remain empty rather than fabricating lineage.

## Implemented FHIR contract

All external routes require an app-only Entra token with `Fhir.Read`.
`GET /fhir/metadata` returns an experimental R4 CapabilityStatement. JSON is
the only response encoding. The service supports `GET /fhir/{type}/{id}` and
`GET /fhir/{type}` for:

| Resource | Search parameters |
|---|---|
| Patient | `_id`, `identifier` |
| Encounter | `_id`, `patient`, `status` |
| Condition | `_id`, `patient`, `encounter`, `code`, `clinical-status` |
| Observation | `_id`, `patient`, `encounter`, `code`, `status` |
| ServiceRequest | `_id`, `patient`, `encounter`, `code`, `status` |
| DiagnosticReport | `_id`, `patient`, `encounter`, `code`, `status` |
| Provenance | `_id` |

- One exact value per parameter, combined with AND. No repeats or comma lists.
- `patient`/`encounter` accept a logical ID or a matching local relative reference.
  Absolute, chained and versioned references are rejected.
- Tokens accept `code`, `system|code`, or `|code` (no system). Identifier uses
  the same syntax with an identifier value. System-only tokens are not supported.
  Searches inspect **all** codings/identifiers, not just the first indexed coding.
- `_count` defaults to 50, range 1-100. `_offset` is an explicitly limited POC
  paging control, range 0-10000. Follow the returned `next` link.
- Pages are sorted by logical ID over live Gold data, not snapshot-isolated.
  Concurrent changes can alter page membership. Totals are deliberately omitted.
  Exceeding the paging window returns an error rather than a misleading complete result.
- `_format=json`/JSON media types and `_pretty=true|false` are supported.
- Writes, transaction/batch POSTs, POST `_search`, history/vread, FHIR
  Subscription CRUD/delivery, includes/revincludes, sorting, date searches,
  chains, modifiers, `_summary`, `_elements`, terminology operations and
  SMART-on-FHIR/patient-level authorization are **not** implemented.
- Unsupported search input returns 400; missing resources return 404; Gold
  failures return 503; malformed/inconsistent Gold resources return 502, all as
  OperationOutcome. The backend rejects writes with 405. APIM publishes GET
  operations only, so other verbs can be rejected by the gateway before HAPI.

Gold is append-only. [GoldFhirCurrent](../fabric/kql/DatabaseSchema.kql)
selects the latest `EventTime` for each resource type/ID **before** applying
search filters. Producers must use strictly increasing event times for
different versions of a logical resource. Equal-time replays must have identical
payloads; conflicting equal-time versions and deletion/tombstone semantics are
outside this POC. Readiness actually queries Gold and cannot become healthy
solely because the Java process started.

## ePNA fixtures

The [generated sample files](../samples/epna) contain five R4 **collection**
Bundles, corresponding NDJSON event files and a phase manifest:

| Phase | Synthetic workflow | Expected eligible encounters |
|---|---|---|
| admission | Three ED admissions; pending patient-specific monitoring | none |
| qualification | Patients 1 and 3 qualify; patient 2 immediately stops monitoring | `epna-e1`, `epna-e3` |
| monitoring | Updated oxygen observation, vitals, radiology order/result/report | `epna-e1`, `epna-e3` |
| discharge | Patient 1 encounter finishes and monitoring stops | `epna-e3` |
| transfer | Patient 3 leaves ED scope (`IMP`); monitoring stops | none |

There are 31 resource updates representing 22 distinct resources, plus ten
monitoring signals. Provenance connects every phase to its resource updates.
Monitoring state is readable as a coded Observation. It is **not** presented as
a working FHIR Subscription engine. The selected transfer behavior is explicitly
the demo's "stop monitoring when leaving ED" branch, not a clinical policy.

No pneumonia scores, thresholds, diagnoses, treatment recommendations or
clinician approvals are inferred from these fixtures. All resources are
synthetically tagged; signal policy is `synthetic-fixture-v1-not-clinical`.
The scripts reject non-demo IDs/tags and dangling references before publication.
Automated Java validation checks core R4 structure offline with terminology
checks disabled; this is not terminology or implementation-guide certification.

The publisher unwraps Bundle entries into individual JSON envelopes. **Do not
POST a Bundle to HAPI or send a Bundle/JSON array as one Eventstream event.**
The ingestion mapping expects `EventKind`, `EventTime`, `SourceEventId`,
`SourceMessageType`, `Resource`, and `Signal`.

## Deployment

Commands below run from the repository root in PowerShell. They create billable
resources only when an operator explicitly executes the deployment steps.

### 1. Prepare Fabric Gold

Use a dedicated synthetic-data workspace/database and an approved Fabric
capacity. Follow the root [Fabric prerequisites](../README.md) and configure
[the example Bicep parameters](../infra/main.example.bicepparam).
Keep `enableActivatorRule = false`.

```powershell
az deployment group create `
  --resource-group <fabric-bootstrap-resource-group> `
  --template-file .\infra\main.bicep `
  --parameters .\infra\main.bicepparam
```

The deployment outputs the Eventstream ID as well as the existing Fabric IDs.
Its [topology](../fabric/eventstream/GoldFhirEventstream.template.json) uses the
documented Eventhouse **item ID**, `DirectIngestion`, table `GoldFhirEvent` and
mapping `GoldFhirEventJson`. Reconciliation owns these POC definitions/update
policies; do not apply over unrelated production schemas.

In Fabric, verify the source/stream/destination are running. Copy the actual
Gold **query URI** and KQL database name from its connection details. Do not
substitute the ingestion URI, Eventstream endpoint or Fabric REST endpoint.
The custom endpoint's **Event Hubs protocol** connection string is a secret;
keep it in a secret store or an ephemeral environment variable, never Terraform,
source, a runtime JSON file or a command-line argument.

### 2. Provision APIM, private AKS and private ACR

Use a fresh Terraform state; read the [adoption warning](../terraform/ADOPTION.md).
Fill in the explicit tenant/subscription/operator/contact, Gold query URI and
approved reader service-principal object IDs.

```powershell
Copy-Item .\terraform\terraform.tfvars.example .\terraform\dev.tfvars
# Edit dev.tfvars and configure the approved remote state backend.
Set-Location .\terraform
terraform init
terraform validate
terraform test
terraform plan -var-file=dev.tfvars -out=deployment.tfplan
# Review and approve the saved plan before executing:
terraform apply deployment.tfplan
terraform output -json fhir_runtime > ..\hie.runtime.json
terraform output -raw gold_reader_grant_kql
Set-Location ..
```

As a Gold database administrator, run the emitted `.add database ... viewers`
command in the named KQL database. Enable the relevant Fabric tenant support
for the managed identity/service principal. This native data permission is
separate from ARM RBAC. Do not grant the pod database administrator/ingestor
or workspace Contributor privileges to make a failed query succeed.

### 3. Build, push and deploy the actual HAPI image

Use Docker, Azure CLI, kubectl and kubelogin on an approved runner that can
reach the **private ACR and AKS API**, including private DNS. A normal public
workstation cannot push to this registry. Do not enable public registry access
or AKS local accounts as a workaround.

```powershell
$runtime = Get-Content .\hie.runtime.json -Raw | ConvertFrom-Json
$tag = 'epna-v1'
docker build --platform linux/amd64 -f .\services\hapi-fhir\Dockerfile `
  -t "$($runtime.acr_login_server)/hapi-fhir:$tag" .
az acr login --subscription $runtime.subscription_id --name $runtime.acr_name
docker push "$($runtime.acr_login_server)/hapi-fhir:$tag"
$digest = az acr repository show --subscription $runtime.subscription_id `
  --name $runtime.acr_name --image "hapi-fhir:$tag" --query digest -o tsv
.\scripts\deploy-hapi.ps1 -RuntimeFile .\hie.runtime.json -ImageDigest $digest
```

Check every command's exit code before continuing in automation. The Docker
build runs the Java tests, including actual HAPI HTTP interactions and core R4
Bundle validation. The build stage is native to the builder; the Java artifact
is portable and the final image targets the x64 AKS node pool.

[The Kubernetes manifest](../kubernetes/hapi-fhir.yaml) uses two stateless
replicas, non-root/read-only containers, workload identity, dependency-aware
readiness, an internal load balancer and APIM-subnet-only ingress. Its image is
pinned by **digest**, not `latest`. The deployment script isolates and cleans up
its temporary kubeconfig, waits for readiness and verifies the expected private
load-balancer IP. It never builds an upstream JPA image and hopes Kusto acts as JDBC.

### 4. Publish and read the synthetic workflow

Install the publisher dependency in an isolated environment:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\scripts\requirements.txt
python .\scripts\epna.py validate
python .\scripts\epna.py publish --phase qualification --dry-run
New-Item -ItemType Directory -Path .\.generated -Force | Out-Null
python .\scripts\epna.py generate --output-dir .\.generated\epna --start-time now
```

The committed fixtures have fixed timestamps for reproducible tests. `now`
generates a compressed four-minute timeline ending now, so the five-minute
Activator window can be exercised. Generate once per demo and publish phases
in order without editing event IDs/times. Keep the rule disabled: this is test
state, not a clinician-approved live qualification signal.

Inject `GOLD_EVENTSTREAM_CONNECTION_STRING` from the Fabric custom endpoint's
Event Hubs details and `FHIR_ACCESS_TOKEN` from an approved client credential
flow for the Terraform `api_token_scope`. Prefer managed identity or certificate
credentials for clients. A normal ARM/Graph Azure CLI token has the wrong
audience. Neither secret is generated or written by Terraform.

```powershell
.\.venv\Scripts\python.exe .\scripts\epna.py publish --samples-dir .\.generated\epna `
  --phase admission --confirm-synthetic
python .\scripts\epna.py smoke --samples-dir .\.generated\epna --phase admission `
  --base-url $runtime.fhir_base_url
```

Repeat publish then smoke for `qualification`, `monitoring`, `discharge`, and
`transfer`. On `qualification`/`monitoring`, inspect `EpnaAlertCandidates()` for
the two expected IDs while the events are within five minutes. After discharge
only patient 3 is eligible; after transfer none are. Finally run
[EpnaAcceptance.kql](../fabric/kql/EpnaAcceptance.kql) in Gold to verify typed
projections and persisted latest states, not merely an expired alert window.
All thirteen result columns must be `true`; false/null is a failed check.

The smoke command checks **every expected resource's complete JSON** through
APIM, metadata, anonymous rejection, paging, error shapes and exclusion of a
stale in-progress encounter after discharge. A publish acknowledgment is not
proof of persistence: it waits for eventual ingestion and fails on timeout.
The script does not follow HTTP redirects with bearer credentials.

Example supported reads:

```text
GET /fhir/Patient/epna-p1
GET /fhir/Patient?identifier=https%3A%2F%2Fexample.org%2Ffhir%2Fidentifier%2Fdemo-xmrn%7CSYNTHETIC-001
GET /fhir/Encounter?patient=epna-p1&status=in-progress
GET /fhir/Observation?patient=epna-p1&encounter=epna-e1&code=http%3A%2F%2Floinc.org%7C59408-5
GET /fhir/DiagnosticReport?patient=epna-p1
GET /fhir/Provenance/epna-provenance-monitoring
```

For replay testing, resend an unchanged earlier event file **after** a later
phase. Gold's latest state must remain unchanged. At-least-once transport can
create duplicate physical rows; current-resource and signal projections dedupe
logically by event time. This does not provide transactional clinical workflow
delivery, exactly-once notification or cross-resource atomic Bundle updates.

### 5. Validate locally and operate

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s .\tests -p 'test_epna.py' -v
Set-Location .\services\hapi-fhir
mvn --batch-mode --no-transfer-progress verify
Set-Location ..\..
Set-Location .\terraform
terraform fmt -check -recursive
terraform validate
terraform test
Set-Location ..
az bicep build --file .\infra\main.bicep --stdout > $null
```

Java 21/Maven are needed only for a native build; Docker runs the same Java
checks without a host JDK. `-RenderOnly` on the deployment script permits
offline manifest inspection without cluster access.
Without the publisher dependencies, its two SDK-specific Python tests are
explicitly skipped; the isolated environment above runs all twelve tests.

Investigate failures rather than accepting an empty Bundle as a fallback:

- Image pull/API access: private DNS, runner connectivity, ACR digest and kubelet role.
- Readiness/503: workload identity namespace/service account/client ID, native
  Gold viewer grant, query URI/database, schema/function deployment and egress.
- Ingestion lag: Eventstream destination health, mapping, Kusto ingestion
  failures and `GoldFhirRejectedEvent`. Malformed transport JSON can fail before
  quarantine; monitor both ingestion failures and rejected-envelope rows.
- Missing typed rows: transactional update-policy failures; do not turn
  `IsTransactional` off to hide schema errors.
- APIM 401/403: tenant, v2 audience, approved client app-role assignment/token role.

Gold errors are logged with a generated Kusto request ID and error class, not
clinical query values or raw service exceptions. No access/request-body logging
or APIM body diagnostics are enabled by this POC.

## Production gates and verification boundary

This is a **synthetic-data POC**, not a production clinical monitoring system.
APIM terminates external TLS; its private hop to HAPI is HTTP. Before PHI use,
implement and validate internal TLS/mTLS, fine-grained patient/purpose/consent
authorization, audited access, approved retention/purge/recovery, terminology
and implementation-guide validation, deletion semantics, deterministic source
versioning, performance budgets and production availability/SLA sizing.
Database viewer access covers the whole dedicated Gold database, not individual
patients. It must not be treated as patient-scoped authorization.

FHIR Subscription lifecycle, durable notifications, acknowledgments, retries,
escalation and stale-subscription reconciliation remain separate work. The
Activator rule remains disabled until the original clinical/governance review
is satisfied; loading these fixtures is not that approval.

Local tests/mocked Terraform plans and Bicep compilation do not verify Azure
quotas, tenant policies, Fabric API acceptance, data-plane permissions or a
live Eventstream-to-APIM round trip. Those require the explicit deployment and
cloud acceptance steps above. No secrets or actual Azure/Fabric resources are
created merely by generating this implementation.

## Authoritative implementation references

- [HAPI plain server and resource providers](https://hapifhir.io/hapi-fhir/docs/server_plain/introduction.html)
- [HAPI search operations](https://hapifhir.io/hapi-fhir/docs/server_plain/rest_operations_search.html)
- [Fabric Eventstream direct-ingestion API walkthrough](https://learn.microsoft.com/en-us/fabric/real-time-intelligence/event-streams/api-kusto-pull-destination)
- [Fabric Eventstream public definition](https://learn.microsoft.com/en-us/rest/api/fabric/articles/item-management/definitions/eventstream-definition)
- [Custom endpoint source and Event Hubs protocol](https://learn.microsoft.com/en-us/fabric/real-time-intelligence/event-streams/add-source-custom-app)
- [Kusto client authentication](https://learn.microsoft.com/en-us/kusto/api/get-started/app-authentication-methods?view=microsoft-fabric)
- [Native Kusto database roles](https://learn.microsoft.com/en-us/kusto/management/manage-database-security-roles?view=microsoft-fabric)
