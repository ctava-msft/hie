# Epic ADT and patient-specific Activator delivery

## Import provenance and scope

The complete [Fabric Activator delivery service](../services/fabric-rt-actuator)
was copied from [ctava-msft/fabric-rt-actuator](https://github.com/ctava-msft/fabric-rt-actuator)
at commit [`94626e706d93092557b7eaaa1830fd4b93d34cec`](https://github.com/ctava-msft/fabric-rt-actuator/tree/94626e706d93092557b7eaaa1830fd4b93d34cec).
The original import included all 32 upstream tracked files: source, tests,
configuration examples, Docker packaging, AKS manifests, and Service Bus Bicep.
The service has since been extended with Power Automate intake, a custom
connector definition, and standalone API Management infrastructure described below.
The source repository's Git history is not imported. No upstream license file
was present at that commit; this copy does not introduce a new license.

This is a **delivery/action-integration POC**, not a working Epic interface or
patient subscription engine. It does not change the existing HAPI FHIR service,
Gold schema, Fabric templates, or root Azure deployments.

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
                   -> Power Automate custom action [tenant binding required]
                   -> API Management -> POST /api/webhooks/power-automate
                   -> Azure Service Bus -> delivery worker -> downstream HTTPS API
```

The service implements webhook intake, queuing, delivery, and a bounded
existing-rule editor, and now supplies the Power Automate connector/gateway
assets. The flow must still be created and bound in the target tenant. It does
**not** implement ADT ingestion/normalization or the subscription controller. The existing
[FHIR Gold POC](./fhir-gold-poc.md) also starts with synthetic Gold events, not a
production Epic feed.

### Available now

| Surface | Imported behavior |
|---|---|
| `POST /api/webhooks/activator` | Accepts a JSON object up to 65,536 bytes with a webhook `X-API-Key` and stable `Idempotency-Key`; confirms broker acceptance with HTTP 202. It is not a raw HL7, Base64, MLLP, or Epic-specific endpoint. |
| `POST /api/webhooks/power-automate` | Accepts exactly two text fields, `eventId` and `payloadJson`, using the webhook key. The entire UTF-8 envelope is limited to 65,536 bytes. Validates and forwards the embedded JSON object's bytes unchanged, reusing `eventId` for broker/downstream idempotency; confirms broker acceptance with HTTP 202. |
| Power Automate integration assets | OpenAPI 2.0 connector plus Bicep that adds an HTTPS, subscription-protected intake API to existing API Management. The tenant-specific Activator trigger/flow binding is a required setup step, not deployed by ARM. |
| Downstream delivery | Forwards the unchanged body to one configured HTTPS `DOWNSTREAM_URL`, optionally using `DOWNSTREAM_BEARER_TOKEN`. Delivery is at least once with bounded retries and dead-lettering, not exactly once. |
| Rule management | Reads an existing Fabric Reflex definition and updates one numeric `temperatureC` condition in one existing rule/trigger entity. Requires the separate admin key, reviewed checksum, and explicit dry run. |
| Deployment records | Stores before/desired definitions and deployment records, verifies changes by read-back, and supports reviewed whole-definition rollback. |

The rule editor does not create, clone, enable, disable, or delete patient rules.
It does not maintain patient/encounter subscriptions or choose destinations per
patient. A patient identifier in a webhook body is forwarded data, not a routing
or authorization instruction. No patient-specific HTTP API is added by this copy.

**Java does not need to call Fabric management or downstream APIs through Power
Automate.** Its documented Reflex REST API calls and worker HTTPS calls remain
direct. Power Automate is the supported **Activator-to-HTTP custom action**
adapter in this integration, not a mandatory proxy for Java. See Microsoft's
[custom-action documentation](https://learn.microsoft.com/en-us/fabric/real-time-intelligence/data-activator/activator-trigger-power-automate-flows)
and [Reflex definition API](https://learn.microsoft.com/en-us/rest/api/fabric/reflex/items/update-reflex-definition).

The flow must preserve the same source event/action key across retries and
resubmissions; do not use a generated GUID, timestamp, or flow run ID. Do not
assume arbitrary direct HTTP callbacks are supported in the target Fabric
tenant. The downstream API must persistently deduplicate that key atomically
with its side effect.

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
and [AKS manifest](../services/fabric-rt-actuator/deploy/aks.yaml) are standalone.
The new [Power Automate intake Bicep](../services/fabric-rt-actuator/infra/power-automate.bicep)
also deploys separately; it can reuse the root Terraform stack's API Management.
The root Bicep/Terraform deployments do not deploy these assets automatically.
Follow the service README's **Connecting Activator** section to deploy the
gateway, import the [custom connector](../services/fabric-rt-actuator/power-automate/connector.swagger.json),
and create/bind the Power Automate flow. Configure the existing APIM name, an
APIM-reachable Java HTTPS ingress host, and the webhook key via secure deployment
parameters. Store the API-scoped subscription key in the connector connection,
not in Java or the Activator definition. No live cloud flow is created by the
Azure template.
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
before connecting a real feed. The automated tests validate the service and connector/gateway contracts,
including Power Automate envelope validation and unchanged delivery semantics,
not live Epic ingestion, tenant flow binding, Fabric clinical rule evaluation,
or the unimplemented patient subscription lifecycle.
