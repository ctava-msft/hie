# AKS Medallion Service

One Java 21 Spring Boot image implements the Bronze, Silver, and Gold roles.
`MEDALLION_ROLE` selects an independently deployed stage. Changes-10 calls for
separate internal Epic HL7 and external USCDI pipelines:

```text
Each source -> its Event Hub -> AKS Bronze -> ADLS Bronze
  -> Service Bus -> AKS Silver -> ADLS Silver -> Service Bus
  -> AKS Gold -> PostgreSQL FHIR event + current state
                      |-> AKS HAPI FHIR, read-only
                      |-> Fabric replication -> Clinical NRT analytics (planned)
```

Isolate event hubs, consumer groups, queues, ADLS paths and identities per
pipeline. Deploy separate PostgreSQL databases, or explicitly design source
isolation and collision-free FHIR IDs before sharing one. The checked-in
manifest and deployment script currently deploy only a single ADX pipeline;
they do not provision this two-pipeline PostgreSQL design.

## What works today

| Role | Input | Commit | Output |
|---|---|---|---|
| `bronze` | Event Hub | ADLS Bronze JSON | `bronze-ready` pointer |
| `silver` | `bronze-ready` | ADLS Silver JSON | `silver-ready` pointer |
| `gold`, `GOLD_BACKEND=adx` (default) | `silver-ready` | ADX `GoldEvent` | ADX projection |
| `gold`, `GOLD_BACKEND=postgresql` | `silver-ready` | PostgreSQL event and current FHIR resource, atomically | HAPI-readable projection |

Messages are completed only after the stage succeeds. PostgreSQL uniqueness
on `(source, event_id)` makes identical replay idempotent and rejects a
conflicting reuse of an event ID. Current state uses FHIR `meta.lastUpdated`:
newer versions replace older ones, older arrivals cannot roll back current
state, and conflicting payloads at the same timestamp are rejected. Event
history and current state commit together.

PostgreSQL Gold currently accepts one JSON FHIR resource per Silver record,
with a valid logical `id`, a supported `resourceType`, and an explicitly
supplied timezone-qualified `meta.lastUpdated` with no sub-microsecond
precision. Supported types are Patient, Encounter, Condition, Observation,
ServiceRequest, DiagnosticReport and Provenance. Producers must supply
strictly increasing timestamps for different versions of the same resource,
use identical JSON for equal-version replays, and agree on globally unique IDs
in any shared database. Deletes/tombstones, cross-source identity matching and
FHIR write operations are not supported. Shape checks are not full FHIR,
terminology, clinical or implementation-guide validation.

The Silver processor currently accepts JSON objects only; it does **not** parse
HL7 v2, CDA or USCDI, validate FHIR, or perform approved clinical mappings.
Unsupported Gold payloads are dead-lettered with a sanitized reason. Do not
enable clinical consumers until the required mappings and controls are
implemented and approved.

The [planned HAPI MDM patient identity rules](../../docs/azure-aks-medallion-architecture.md#planned-silver-patient-identity-rules-with-hapi-mdm)
specify exact matching on a configured enterprise identifier and review of
unresolved records. HAPI JPA, MDM matching, and the review workflow are not
implemented in this service.

## PostgreSQL and HAPI configuration

Provision PostgreSQL separately with private networking, verified TLS,
backups and approved access controls. Apply
[postgresql-gold.sql](../../infra/aks-medallion/postgresql-gold.sql) as a
schema administrator; it creates the `hie_gold.fhir_event` and
`hie_gold.fhir_current` tables. This is a custom projection, **not** the HAPI
JPA schema. Grant the Gold writer schema `USAGE`, table `SELECT`/`INSERT` on
`fhir_event` and `SELECT`/`INSERT`/`UPDATE` on `fhir_current`. Grant HAPI
schema `USAGE` and only `SELECT` on `fhir_current`. Use separate identities.
Manage migrations and grants independently of the application.

Configure the Gold deployment with `GOLD_BACKEND=postgresql` and HAPI with
`GOLD_STORE=postgresql`. Each requires:

- `POSTGRES_HOST`: server DNS name, without protocol or port;
- `POSTGRES_DATABASE`, `POSTGRES_USER`, and `POSTGRES_PASSWORD`;
- optionally `POSTGRES_SSL_ROOT_CERT`: path to the trusted CA certificate.

The JDBC clients use port 5432 and `sslmode=verify-full`. Mount a trusted CA
at the driver's default location or set the certificate path. Supply passwords
via an approved secret mechanism, not a ConfigMap or source control. The
current implementation uses database password authentication and opens a JDBC
connection per operation; managed-identity token authentication and connection
pool sizing/rotation are not implemented.

The [HAPI FHIR service](../hapi-fhir) uses its existing read/search-only R4
providers and parameterized PostgreSQL queries. It is not a full HAPI FHIR JPA
server. It defaults to its existing Fabric Eventhouse backend unless
`GOLD_STORE` is set. Its current
[deployment script](../../scripts/deploy-hapi.ps1) and
[manifest](../../kubernetes/hapi-fhir.yaml) configure Eventhouse, not the
PostgreSQL option; adapt deployment configuration and secret mounting before
using PostgreSQL.

Fabric Mirroring or an approved CDC/ADF mechanism must be configured
separately. Verify PostgreSQL service, region, type support (including JSONB),
keys, deletes, connectivity and latency before choosing a connector. Fabric
is a downstream read-only analytical copy, not a clinical write path.

## Existing ADX deployment

The original [Bicep template](../../infra/aks-medallion/main.bicep) and
[deployment script](../../scripts/deploy-aks-medallion.ps1) provision and
configure the ADX variant, not PostgreSQL or Fabric replication:

```powershell
Copy-Item .\infra\aks-medallion\main.example.bicepparam `
  .\infra\aks-medallion\main.bicepparam
.\scripts\deploy-aks-medallion.ps1 `
  -ResourceGroup rg-hie-medallion `
  -BicepParameters .\infra\aks-medallion\main.bicepparam `
  -ImageTag 0.1.0
```

AKS workloads use `DefaultAzureCredential` for Event Hubs, Service Bus and
ADLS; the ADX option also requires its database ingestor permission.

## Verification and remaining work

Run each service's Maven tests. PostgreSQL integration tests run only when
`HIE_TEST_POSTGRES_URL`, `HIE_TEST_POSTGRES_USER`, and
`HIE_TEST_POSTGRES_PASSWORD` point to an **isolated test database** with the
schema applied. Never use a clinical database for tests.

Before production, implement and validate:

- separate Epic/USCDI deployment topology, PostgreSQL provisioning and Fabric
  replication;
- approved HL7/USCDI-to-FHIR mappings, validation and terminology;
- ADLS Gold storage and a transactional outbox with Gold/action queue
  publishers and consumers;
- reconciliation of storage-to-queue handoffs, DLQ re-drive and replay;
- connection pooling, secret rotation, availability, disaster recovery and
  replication lag monitoring;
- PHI controls, retention, authorization, load and failover testing; and
- clinical, security, privacy, architecture and operations approvals.
