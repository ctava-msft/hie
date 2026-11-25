# AKS Medallion Service

One Java 21 Spring Boot image implements Bronze, Silver, Gold, and subscription
relay roles.
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
| `silver`, `SILVER_HAPI_ENABLED=true` | FHIR R4 resource | HAPI MDM and ADLS Silver JSON | Resolved Patient or patient-linked resource |
| `gold`, `GOLD_BACKEND=adx` (default) | `silver-ready` | ADX `GoldEvent` | ADX projection |
| `gold`, `GOLD_BACKEND=postgresql` | `silver-ready` | PostgreSQL event and current FHIR resource, atomically | HAPI-readable projection |
| `gold`, `GOLD_BACKEND=postgresql-hapi` | `silver-ready` | PostgreSQL event/current state, then HAPI JPA API | HAPI JPA resource |

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
in any shared database. Deletes/tombstones are not supported. Shape checks are not full FHIR,
terminology, clinical or implementation-guide validation.

The Silver processor currently accepts JSON objects only; it does **not** parse
HL7 v2, CDA or USCDI, validate FHIR, or perform approved clinical mappings.
Unsupported Gold payloads are dead-lettered with a sanitized reason. Do not
enable clinical consumers until the required mappings and controls are
implemented and approved.

The [HAPI MDM patient identity rules](../../docs/azure-aks-medallion-architecture.md#planned-silver-patient-identity-rules-with-hapi-mdm)
specify exact matching on a configured enterprise identifier. The opt-in Silver
integration checks links and duplicates before promotion; the steward review,
correction, and re-drive workflow remains operational work.

## Opt-in HAPI JPA integration

These settings do not deploy HAPI JPA or configure its security. Provision
separate Silver MDM and Gold R4 JPA servers and databases for each pipeline,
and protect them with authenticated TLS, endpoint authorization, auditing,
private networking, backups and supported upgrade/migration procedures. The
upstream HAPI starter has no security implementation and must not be exposed
as-is. Configure MDM only on the Silver server, with the exact-identifier
rules below; configure the Gold server for client-assigned IDs, ETag support,
and the separately approved REST-hook subscriptions. The existing read-only
HAPI adapter is not the JPA server.

- Set `SILVER_HAPI_ENABLED=true`, `HAPI_SILVER_URL` and `HAPI_SILVER_TOKEN`.
  Set `PATIENT_IDENTIFIER_SYSTEM` to the approved absolute system URI, matching
  the MDM rules exactly. Configure the Silver replicas with the same
  `POSTGRES_HOST`, `POSTGRES_DATABASE`, `POSTGRES_USER`, and `POSTGRES_PASSWORD`
  so their advisory locks coordinate. TLS settings are as described below.
- Set `GOLD_BACKEND=postgresql-hapi`, `HAPI_GOLD_URL`, and `HAPI_GOLD_TOKEN`.
  Gold also needs the custom PostgreSQL schema and credentials below. Store
  tokens and passwords in an approved secret mechanism. HAPI URLs require
  HTTPS; HTTP is accepted only for `127.0.0.1` synthetic tests.
- Limit write access to these integration identities. All Gold replicas must
  share the same projection database, and all Silver replicas must share the
  Silver coordination database. Verify create and ETag behavior against the
  pinned HAPI version: the integration uses `If-Match: W/"0"` for creation
  and the returned ETag for updates.
- For numeric-only non-Patient IDs, configure a new Gold JPA server with
  `hapi.fhir.client_id_strategy=ANY`; the starter's default `ALPHANUMERIC`
  rejects numeric client-assigned IDs. The pinned `hapiproject/hapi:v8.12.0-2`
  accepts numeric Observation IDs with `HAPI_FHIR_CLIENT_ID_STRATEGY=ANY`.
  Plan and validate ID-strategy changes before applying them to existing data.

Silver requires a stable source FHIR logical ID, one configured enterprise
identifier for Patients, and a timezone-qualified `meta.lastUpdated` with at
most microsecond precision. It derives a source Patient ID from source and
logical ID, writes to Silver HAPI, then checks MDM links, duplicates, the
identifier, and source-version markers on the HAPI Golden Patient. It emits
the Golden Patient's contents using a deterministic Gold ID derived from the
MDM Golden ID, and rewrites relative `Patient/<id>` references to that ID.
Non-Patient resources need at least one resolvable Patient reference.
Absolute, contained, identifier-only, and other unsupported references are
held rather than inferred. The processor does not implement full FHIR,
terminology, clinical, or implementation-guide validation.

Missing or conflicting identity and possible matches are dead-lettered with a
sanitized reason. Pending MDM work and transient errors are retried; exhausted
messages also end up in the queue's dead-letter subqueue. Configure monitoring,
restricted steward access, correction and re-drive before use. MDM merge,
unmerge and correction of already promoted references are not automated.
Multiple source events can cause the Golden Patient's source marker to change;
an event whose marker is no longer represented is held for reconciliation,
not silently promoted.

Gold first commits its existing event/current-state transaction, then locks the
current projection and writes it through the Gold JPA FHIR API. The source
timestamp and a payload hash are retained in reserved FHIR extensions so a
retry after a lost response does not write another version. Older events cannot
replace newer current state; equal-version conflicts are rejected. The database
transaction and HTTP write are not atomic, and there is no outbox: reconcile
and re-drive dead letters after an outage. Do not write directly into the HAPI
JPA tables or modify the reserved extensions. Gold source timestamps must be
strictly ordered across source records that resolve to the same Golden Patient.
The MDM and JPA paths have synthetic integration tests but are not approved
for clinical use without full clinical, security and failure-recovery review.

## FHIR subscription relay

The `subscription-relay` role has an opt-in REST-hook receiver at
`PUT /internal/fhir-subscription/{resourceType}/{resourceId}`. HAPI's
subscription endpoint should be the base `/internal/fhir-subscription`; HAPI
appends the resource path. The receiver accepts a single FHIR JSON resource
with `resourceType`, `id`, and `meta.versionId`; it does not support empty
notifications, deletion notifications, or subscription payloads without a
version. It checks these fields but does not perform full FHIR validation.
It returns HTTP 204 only after Service Bus confirms the send, and HTTP 503
when publication fails. This relay does not itself configure subscriptions or
turn the existing read-only HAPI service into a JPA server.

To configure an isolated relay deployment:

- Set `MEDALLION_ROLE=subscription-relay`, `SUBSCRIPTION_RELAY_ENABLED=true`
  and `SUBSCRIPTION_RELAY_QUEUE` to an
  existing queue in `SERVICE_BUS_NAMESPACE`; grant the workload identity
  send access. Use separate queues and identities for the two pipelines.
  The current application still requires the standard Event Hubs and storage
  configuration at startup. Run at least one ready relay replica; do not
  attach it to the Gold worker's KEDA scaler, which can scale to zero.
- Set `SUBSCRIPTION_RELAY_TOKEN` through a secret store, not a ConfigMap or
  source control. The token must contain 32-256 characters from
  `A-Z`, `a-z`, `0-9`, `.`, `_`, `~`, and `-`; generate it randomly. Protect
  access to FHIR Subscription resources because their configured headers can
  contain this secret, and plan coordinated rotation.
- Configure each approved HAPI R4 REST-hook subscription with a FHIR JSON
  payload, the relay base endpoint, `Authorization: Bearer <token>` and a unique
  `X-Hie-Subscription` header containing 1-64 letters, digits, dots or
  hyphens. Configure criteria separately; restrict who can create or change
  subscriptions and which endpoints are permitted.
- Use private networking and authenticated TLS to the receiver; this service
  does not terminate TLS itself. Do not expose the endpoint publicly. The
  request limit defaults to 131072 bytes; `SUBSCRIPTION_RELAY_MAX_BYTES` can
  lower it. Verify the queue's message-size limit and JSON serialization
  overhead. Oversize or malformed requests are rejected and not published.

The Service Bus message body is the resource. Its `idempotencyKey` application
property is a hash of the subscription header, resource type, logical ID and
HAPI version ID. Its message ID additionally hashes the original payload, so
different payloads for the same version are not silently discarded as
duplicates. These hashes are not anonymization; protect queue metadata and
payloads as sensitive data. Consumers must durably deduplicate by
`idempotencyKey`, detect conflicting payloads and process idempotently; Service
Bus duplicate detection alone has a finite window. Configure and verify HAPI's
failure retry and recovery behavior, monitor failures, and reconcile missed
notifications. The upstream HAPI JPA starter uses an in-memory subscription
channel by default, so a durable Service Bus relay alone does not ensure
notifications survive HAPI restarts or work across HAPI replicas. A persistent
HAPI-side delivery mechanism or reconciliation remains necessary. The tested
starter also registers new subscriptions asynchronously: an `active` resource
can exist before its matcher is registered, so writes in that interval may
not produce notifications.

A synthetic test has exercised HAPI JPA with PostgreSQL through the HTTP relay,
but the queue publisher was mocked; actual Azure Service Bus delivery,
recovery, and consumer deduplication have not been verified. Do not use this
relay for clinical delivery until those checks and security and operational
reviews are complete.

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

The HAPI integration tests use `HIE_TEST_MDM_URL` and `HIE_TEST_HAPI_URL` for
isolated local Silver MDM and Gold JPA servers, for example
`http://127.0.0.1:60136/fhir`. Set the PostgreSQL test variables above for the
pipeline tests. The tests create synthetic resources and a subscription;
destroy the test databases afterwards. Configure R4 MDM with the
documented rule template using `https://example.invalid/test-eid` in both
identifier-system locations, and enable REST-hook subscriptions. The HAPI
container must be able to reach `host.docker.internal` at the test's random
HTTP port. These tests were exercised with the `hapiproject/hapi:v8.12.0-2`
starter image and PostgreSQL 16; the subscription test uses a mock publisher,
not Service Bus. The tests cover only a small subset of the required MDM and
delivery cases. Configure client-assigned IDs and REST-hook subscriptions on
the Gold test server, including `hapi.fhir.client_id_strategy=ANY` for numeric
ID tests; configure client-assigned IDs and MDM on the Silver test
server.

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
