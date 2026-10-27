# Epic ADT and patient-specific Activator delivery

## Import provenance and scope

The complete [Fabric Activator delivery service](../services/fabric-rt-actuator)
was copied from [ctava-msft/fabric-rt-actuator](https://github.com/ctava-msft/fabric-rt-actuator)
at commit [`94626e706d93092557b7eaaa1830fd4b93d34cec`](https://github.com/ctava-msft/fabric-rt-actuator/tree/94626e706d93092557b7eaaa1830fd4b93d34cec).
All 32 upstream tracked files are preserved unchanged, including source, tests,
configuration examples, Docker packaging, AKS manifests, and Service Bus Bicep.
The source repository's Git history is not imported. No upstream license file
was present at that commit; this copy does not introduce a new license.

This is a **copy-and-document integration**, not a working Epic interface or
patient subscription engine. It does not change the existing HAPI FHIR service,
Gold schema, Fabric templates, or Azure deployments.

## Position in the HIE architecture

The intended end-to-end flow is:

```text
Epic ADT -> Azure Event Hubs -> Fabric Eventstream
         -> Bronze: original HL7 Base64
         -> Silver: decoded/normalized HL7 JSON
         -> Gold: patient/encounter events
              |
              +-> admission/discharge subscription controller [not implemented]
              |    -> patient/encounter-specific Activator configuration
              |
              +-> Activator detection
                   -> supported Pipeline / User Data Function action adapter
                   -> POST /api/webhooks/activator
                   -> Azure Service Bus -> delivery worker -> downstream HTTPS API
```

The imported service implements webhook intake, queuing, delivery, and a bounded
existing-rule editor. It does **not** implement the ADT ingestion/normalization,
subscription controller, or Activator action adapter shown above. The existing
[FHIR Gold POC](./fhir-gold-poc.md) also starts with synthetic Gold events, not a
production Epic feed.

### Available now

| Surface | Imported behavior |
|---|---|
| `POST /api/webhooks/activator` | Accepts a JSON object up to 65,536 bytes with a webhook `X-API-Key` and stable `Idempotency-Key`; confirms broker acceptance with HTTP 202. It is not a raw HL7, Base64, MLLP, or Epic-specific endpoint. |
| Downstream delivery | Forwards the unchanged body to one configured HTTPS `DOWNSTREAM_URL`, optionally using `DOWNSTREAM_BEARER_TOKEN`. Delivery is at least once with bounded retries and dead-lettering, not exactly once. |
| Rule management | Reads an existing Fabric Reflex definition and updates one numeric `temperatureC` condition in one existing rule/trigger entity. Requires the separate admin key, reviewed checksum, and explicit dry run. |
| Deployment records | Stores before/desired definitions and deployment records, verifies changes by read-back, and supports reviewed whole-definition rollback. |

The rule editor does not create, clone, enable, disable, or delete patient rules.
It does not maintain patient/encounter subscriptions or choose destinations per
patient. A patient identifier in a webhook body is forwarded data, not a routing
or authorization instruction. No patient-specific HTTP API is added by this copy.

Activator must call a supported action adapter that supplies the webhook headers
and preserves the same idempotency key across retries. Do not assume arbitrary
direct HTTP callbacks are supported in the target Fabric tenant. The downstream
API must persistently deduplicate that key atomically with its side effect.

### Existing ePNA deployment is separate

The existing [ePNA Activator template](../fabric/activator/ReflexEntities.template.json)
is an email-based alert, not a compatible patient-specific temperature-rule
template. The imported temperature editor must not be used to configure it.
Keep `enableActivatorRule = false` until its own reviewed acceptance checks pass.

The [Fabric bootstrap script](../infra/scripts/deploy-fabric.ps1) reconciles the
complete Activator definition. Running it against an item also managed by this
service can overwrite service-managed changes. Use a separate reviewed Activator
item or coordinate one authoritative definition writer. The service's checksum
guard and filesystem lock do not coordinate with the bootstrap script or UI.

## Local setup and deployment

Run the following from the repository root with Java 21 and Maven 3.9+:

```powershell
Set-Location .\services\fabric-rt-actuator
mvn -B -ntp verify

# First-time configuration only; do not overwrite an existing .env.
Copy-Item .env.example .env
# Configure .env before starting the service.
mvn spring-boot:run
```

The working directory is important: the service reads its local `.env` and
defaults its audit directory to `.\data\deployments`. Its nested
[Git ignore rules](../services/fabric-rt-actuator/.gitignore) exclude local
secrets, audit data, and build output.

Before startup, provision a Standard/Premium Service Bus queue, replace both API
keys with distinct random values of at least 32 characters, and configure the
namespace and real HTTPS downstream URL. For rule management, configure the
existing Fabric workspace/Activator IDs and grant the identity Fabric access.
Service Bus permissions and Azure subscription RBAC do not grant Fabric access.
The service uses ports 8080 and 8081; avoid conflicts with another locally
running service, including HAPI.

Follow the imported [service README](../services/fabric-rt-actuator/README.md)
for the webhook examples, queue guarantees, rule workflow, Docker Compose, and
AKS instructions. Use the service directory as the Docker build context.
The [Service Bus template](../services/fabric-rt-actuator/infra/servicebus.bicep)
and [AKS manifest](../services/fabric-rt-actuator/deploy/aks.yaml) are standalone;
the root Bicep/Terraform deployments do not deploy them automatically.
Keep management routes internal and use approved secret delivery, TLS, identity,
payload retention, and audit-storage controls before handling patient data.

## ADT integration boundary still to implement

The patient-by-patient workflow needs an agreed Epic interface and durable
subscription lifecycle in addition to this delivery service:

- Normalize the site's admission/discharge events into an agreed contract with
  source/facility, patient and encounter identity, event time/version, and a stable
  source-event key. Do not assume all Epic interfaces use the same event mapping.
- Persist subscription intent and its Activator entity mapping per patient
  **and encounter**, including admission/discharge state and configuration
  version. Reconcile repeated admissions idempotently; account for concurrent
  encounters, late/out-of-order events, corrections, and discharge cancellations.
- On admission, reconcile the approved patient/encounter rule and supported
  action binding. On discharge, deactivate only the matching encounter's
  subscription. This requires a reviewed tenant-supported rule definition and
  lifecycle implementation, not repurposing the temperature endpoint.
- Define what happens to actions already queued when discharge occurs.
  Disabling an Activator rule does not retract queued deliveries, and the
  imported worker does not check encounter state before forwarding. If stale
  actions must be suppressed, enforce durable lifecycle checks before downstream
  side effects; no post-discharge suppression guarantee exists in this copy.
- Use opaque action keys without direct patient identifiers in headers/logs.
  Agree the downstream payload, authorization, idempotency, and retry contract;
  no clinical thresholds or treatment decisions are inferred by this import.

Use synthetic events to verify admission, repeated admission, simultaneous
patients/encounters, discharge, delayed events, action retries, and dead letters
before connecting a real feed. The imported automated tests validate the service
contracts, not live Epic ingestion, Fabric clinical rule evaluation, or the
unimplemented patient subscription lifecycle.
