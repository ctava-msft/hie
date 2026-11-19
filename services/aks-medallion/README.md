# AKS Medallion Service

One Java 21 Spring Boot image implements the Bronze, Silver, and Gold roles.
The `MEDALLION_ROLE` environment variable selects one independently deployed
pipeline stage:

```text
Event Hubs -> Bronze AKS -> ADLS Bronze -> Service Bus -> AKS Silver
          -> ADLS Silver -> Service Bus -> AKS Gold -> ADX
```

## Scope

The initial implementation proves durable transport, stage commits, replay
identity, queue dead-lettering, and ADX ingestion. It accepts JSON object
payloads in the Silver normalizer. Production HL7 v2 parsing, FHIR validation,
USCDI+ mapping, terminology translation, and clinical rules require approved,
versioned mapping components. Invalid or unsupported payloads move to the
Service Bus dead-letter queue with a sanitized reason.

## Roles

| Role | Input | Commit | Output |
|---|---|---|---|
| `bronze` | Event Hubs | ADLS Bronze | `bronze-ready` stage pointer |
| `silver` | `bronze-ready` | ADLS Silver | `silver-ready` stage pointer |
| `gold` | `silver-ready` | ADX `GoldEvent` | KQL/Eventhouse-equivalent record |

Queue completion occurs only after the target commit is confirmed. Bronze and
Silver can overwrite the deterministic event blob safely, giving replay an
idempotent storage effect. A Gold event ID is sent to ADX as the stable
idempotency/correlation value.

## Required identities

All stages use `DefaultAzureCredential`. On AKS this resolves workload
identity; local development can use Azure CLI. The identity requires:

- Event Hubs Data Owner on the `hie-events` Event Hub;
- Service Bus Data Owner on the namespace;
- Storage Blob Data Contributor on the ADLS account; and
- ADX `Ingestor` on the `hie_gold` database.

The Bicep deployment creates these assignments. Do not add broker or storage
connection strings to Kubernetes configuration.

## Infrastructure and deployment

The Bicep template provisions private AKS, ACR, ADLS Gen2, Event Hubs, Service
Bus Premium, ADX, private endpoints, Log Analytics, KEDA, workload identity,
federation, data-plane RBAC, and the `GoldEvent` schema.

```powershell
Copy-Item .\infra\aks-medallion\main.example.bicepparam `
  .\infra\aks-medallion\main.bicepparam

# Replace the Entra AKS admin group object ID, then run from the repo root.
.\scripts\deploy-aks-medallion.ps1 `
  -ResourceGroup rg-hie-medallion `
  -BicepParameters .\infra\aks-medallion\main.bicepparam `
  -ImageTag 0.1.0
```

The script expects `az`, private-network connectivity to the AKS API, and
`kubectl`. It renders the checked-in manifest using Bicep outputs and applies
it to the cluster.

## Verification

```powershell
kubectl -n hie-medallion get deployments,pods,scaledobjects
kubectl -n hie-medallion logs deployment/medallion-bronze
kubectl -n hie-medallion logs deployment/medallion-silver
kubectl -n hie-medallion logs deployment/medallion-gold
```

Send a JSON object to Event Hubs with application properties `eventId`,
`source`, and `eventType`. Verify the corresponding blobs in Bronze and
Silver and query:

```kusto
GoldEvent
| where eventId == "<event-id>"
| project eventId, source, eventType, eventTime, projectedTime, payload
```

## Production gaps

Before clinical production use:

- implement and validate the approved HL7/FHIR and terminology mapping;
- use an outbox or reconciliation process for storage-to-Service-Bus atomicity;
- add field-level PHI controls and approved retention policies;
- prove ADX ingestion status and duplicate behavior under retries;
- add KEDA and ADX load tests against peak and recovery targets;
- formalize replay authorization, action suppression, and DLQ re-drive;
- implement multi-region recovery objectives; and
- complete clinical, security, privacy, architecture, and operations reviews.
