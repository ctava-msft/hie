# Fabric Activator delivery service

Java 21 / Spring Boot application for receiving actions originating from Microsoft
Fabric Activator, durably queuing them in Azure Service Bus, delivering them to a
configurable HTTPS endpoint, and updating the temperature condition in an existing
Activator rule. Includes Docker packaging, AKS manifests, queue infrastructure, and
unit tests.

```text
Activator -> supported action / Pipeline / UDF adapter
          -> POST /api/webhooks/activator
          -> Azure Service Bus -> peek-lock worker -> DOWNSTREAM_URL
                                  |
                                  +-> dead-letter queue

Internal operator -> /api/rules/* -> Fabric REST APIs
                                 -> persistent snapshots + deployment records
```

Activator is the detection layer, not the durable subscription/delivery plane.
This application is a bounded delivery POC, not a patient subscription registry,
dynamic routing engine, clinical decision system, or production clinical sign-off.

## Quick start

Prerequisites: Java 21 and Maven 3.9+, or Docker; a provisioned **Standard or Premium**
Service Bus queue; Fabric access for rule management.

```powershell
Copy-Item .env.example .env
# Edit .env: replace both API keys, the Service Bus namespace, and DOWNSTREAM_URL.
# For Fabric management, also set the real workspace and Activator UUIDs.
az login
mvn verify
mvn spring-boot:run
```

The application loads `.env` from its working directory using Spring's properties
file support. Use plain `KEY=value`, **without quotes, `export`, or inline comments**.
Environment variables override `.env` values. `.env` and local deployment data are
git-ignored. Generate two different random API keys of at least 32 characters;
the template values are placeholders, not suitable credentials.

`DOWNSTREAM_URL=https://example.invalid/activator` is deliberately non-routable.
Replace it before accepting real events. API keys and configuration are loaded at
startup; restart after changing them.

### Configuration

See [`.env.example`](.env.example) for all settings.

| Setting | Purpose |
| --- | --- |
| `WEBHOOK_API_KEY` | Webhook-only `X-API-Key` credential, minimum 32 characters |
| `ADMIN_API_KEY` | Separate rule-management credential, minimum 32 characters |
| `SERVICE_BUS_NAMESPACE` | Fully qualified namespace, e.g. `name.servicebus.windows.net` |
| `SERVICE_BUS_QUEUE` | Existing queue name; default `activator-deliveries` |
| `SERVICE_BUS_CONNECTION_STRING` | Optional local alternative to Azure identity |
| `DOWNSTREAM_URL` | Fixed HTTPS destination; no request-controlled routing or redirects |
| `DOWNSTREAM_BEARER_TOKEN` | Optional downstream bearer credential |
| `WORKER_ENABLED` | Set `false` to pause delivery while still accepting/enqueuing |
| `WORKER_CONCURRENCY` | Parallel deliveries, 1-32; default 4 |
| `FABRIC_WORKSPACE_ID`, `FABRIC_ACTIVATOR_ID` | Existing Fabric workspace and Activator UUIDs |
| `AUDIT_DIRECTORY` | Durable snapshot and deployment-record directory |

Azure authentication uses `DefaultAzureCredential` (Azure CLI locally, Workload
Identity on AKS). For a local service principal, provide `AZURE_TENANT_ID`,
`AZURE_CLIENT_ID`, and `AZURE_CLIENT_SECRET` in `.env` or the process environment.
The application explicitly reads those Spring properties to support `.env`.
Never set a client secret in an AKS workload-identity deployment. A Service Bus
connection string does **not** authenticate Fabric.

The supplied Service Bus infrastructure disables local/SAS authentication. Use
identity with that namespace; a connection string requires a separately managed
namespace that permits local authentication.

## Webhook contract

```http
POST /api/webhooks/activator
Content-Type: application/json
X-API-Key: <WEBHOOK_API_KEY>
Idempotency-Key: synthetic-event-0001

{"eventType":"Observation","encounterId":"synthetic-001","patientAge":18,"temperatureC":39}
```

Body: any JSON **object**, maximum **65,536 bytes**, forwarded unchanged. The
idempotency key is mandatory: 1-128 characters from `A-Z a-z 0-9 . _ : -`.
Use one stable, globally unique key per source event/action combination and reuse
it for every retry of that action. Never reuse a key for a different payload.
Identifiers should be opaque and contain no PHI because they appear in logs.

A successful response is `202 {"eventId":"synthetic-event-0001","status":"queued"}`.
It means broker acceptance was confirmed, **not** that the destination processed
the event. Broker failures/timeouts return `503`; invalid requests return `400`,
oversized bodies `413`, missing/wrong API keys `401`. A publish timeout can mean
the message was already accepted: retry with the **same** key.

PowerShell example:

```powershell
$headers = @{
  "X-API-Key" = "<your-webhook-key>"
  "Idempotency-Key" = "synthetic-event-0001"
}
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/webhooks/activator `
  -Headers $headers -ContentType application/json `
  -Body '{"eventType":"Observation","encounterId":"synthetic-001","patientAge":18,"temperatureC":39}'
```

### Connecting Activator

Do not assume Activator supports arbitrary direct HTTP callbacks in your tenant.
Use a supported action (for example a Fabric pipeline or User Data Function) as
an adapter that POSTs this contract. The adapter must supply both headers above,
preserve the event key across retries, and retry timeout/`503` outcomes. Store its
webhook key securely. If upstream never retries an unacknowledged call, this
application cannot guarantee that call reaches the queue.

## Delivery guarantees and operating limits

* **At least once**, after confirmed enqueue and subject to queue retention,
  availability, and operator remediation of dead letters. There is no atomic
  transaction between an HTTP destination and Service Bus.
* The worker completes the peek-lock message only after destination HTTP `2xx`.
  A destination `202` means that destination has accepted responsibility; it does
  not prove its final business effect completed.
* Network failures, `408`, `429`, and `5xx` leave the message unsettled. When its
  lock expires it is eligible for redelivery. The provided queue uses a 30-second
  lock and 10 maximum deliveries, after which Service Bus dead-letters it.
  This is bounded lock-expiry retry, **not** exponential backoff and does not
  implement the destination's `Retry-After` header.
* Other non-`2xx` statuses (including redirects and permanent `4xx`) dead-letter
  immediately. Redirects are never followed. Fix credentials/configuration before
  replaying rejected messages.
* A crash after destination success but before queue completion can repeat the
  HTTP request. The destination **must persistently deduplicate `Idempotency-Key`
  atomically with its side effect** to prevent duplicate clinical actions.
* The broker also deduplicates producer MessageIds for seven days. This reduces
  duplicate enqueues; it does **not** deduplicate consumer redelivery or provide
  end-to-end exactly-once execution.
* Default TTL is 14 days, with expiration dead-lettering enabled. Messages can
  expire before delivery during a long outage. Accepted events are not retained
  or retried forever. Dead letters require alerting and an operator.
* Ordering is not guaranteed; deliveries run concurrently. No patient/object
  ordering, fan-out, authorization registry, automatic DLQ replay, multi-region
  recovery, or durable consumer-side deduplication is implemented here.

For DLQ replay, inspect failures in Azure Service Bus Explorer, repair the cause,
and resend the **same body** with a **new broker MessageId** and the original
`idempotencyKey` application property. The worker sends that original property
as the downstream `Idempotency-Key`. Otherwise the broker can suppress a replay
within its duplicate-detection window. Remove the original dead letter only after
the replay is accepted; record the operator's replay decision. Do not log/export
real patient payloads. Messages without the property fall back to their MessageId.

Monitor queue depth/age, dead-letter count, delivery attempts, receiver errors,
and destination latency/status externally. Health probes describe application
liveness/readiness, **not** guaranteed Azure connectivity or successful delivery.
The ingress returns failure if publishing cannot be confirmed.

## Activator rule configuration

Start with a working rule authored in the Fabric UI. This application does not
invent the internal Activator entity graph or create new actions. It modifies
one existing numeric `temperatureC` condition in a selected rule/trigger entity,
leaving age conditions, relationships, enabled state, and action bindings alone.
It supports `field` / `property` / `column` selectors and
`value` / `threshold` / `rightValue` numeric operands inside
`payload.definition.instance`, including a JSON-encoded string instance.
Unknown/ambiguous shapes are rejected rather than guessed.

All endpoints below require `X-API-Key: <ADMIN_API_KEY>`.

1. `GET /api/rules/definition` retrieves `{checksum, definition}`. Decode the
   `ReflexEntities.json` part, inspect it, and find the stable entity `id` for
   your existing trigger. Responses contain sensitive configuration: keep this
   endpoint internal.
2. Submit a reviewed dry-run:

   ```http
   POST /api/rules/temperature
   Content-Type: application/json
   X-API-Key: <ADMIN_API_KEY>

   {
     "ruleId": "adult-high-temperature",
     "entityId": "<existing-trigger-entity-id>",
     "version": 3,
     "temperatureC": 39.0,
     "expectedChecksum": "<checksum-from-GET>",
     "dryRun": true
   }
   ```

3. Review the result and resubmit with `"dryRun": false`. Both the checksum and
   the explicit `dryRun` boolean are required. `409` means the current definition
   no longer matches the reviewed baseline. A verified deployment returns
   `deploymentId`, `checksum`, `previousChecksum`, `rollbackReference`, version,
   item/workspace IDs, timestamp, and `result: VERIFIED`. `UNCHANGED` skips the
   write. Dry runs do not create persistent records or rollback references.
   `ruleId` and `version` are audit metadata, not a runtime subscription registry
   or a monotonic-version enforcement mechanism.
4. The service stores complete before/desired definitions and a record on disk
   **before** submission, polls Fabric asynchronous operations, then retrieves
   the definition again and compares checksums. It preserves other definition
   parts. A checksum compares the serialized complete definition, so Fabric
   normalization of payload formatting can cause conservative verification
   failure; inspect the actual definition before retrying.
5. To roll back, first retrieve/review the latest checksum, then:

   ```http
   POST /api/rules/rollback
   Content-Type: application/json
   X-API-Key: <ADMIN_API_KEY>

   {
     "deploymentId": "<rollbackReference-from-original-deployment>",
     "expectedChecksum": "<current-checksum>",
     "dryRun": true
   }
   ```

   Review, then use `"dryRun": false`. This restores the **complete** prior
   definition, including any unrelated rules in that snapshot. It is a new,
   audited deployment and must be reviewed accordingly.

Snapshots/records are in `AUDIT_DIRECTORY`, one UUID-prefixed set per deployment.
If a call fails ambiguously, the record is `UNKNOWN_OR_FAILED` (or remains
`SUBMITTED` after a crash), and the HTTP response is `503`. Reconcile it against
Fabric and the saved desired definition before retry/rollback; the app deliberately
does not automatically roll back an operation that might still be committing.
Unconfirmed submissions return their `deploymentId` in the error response.
If the final audit write fails, reconciliation is likewise required.

The app serializes management calls with an in-process and filesystem lock.
**Operate exactly one authoritative rule writer**; do not concurrently edit
these rules in the Fabric UI or another deployment system. The reviewed checksum
is a stale-read guard, not Fabric-provided transactional compare-and-swap.
The combined AKS deployment is intentionally single-replica/Recreate. For
high availability, split intake/workers from the singleton deployment controller
and implement distributed change coordination before scaling.

### Validation boundary

The service validates the supported JSON structure and exact target uniqueness
before writing, uses Fabric's `updateDefinition` acceptance, and performs
post-write read-back verification. The public Reflex API reference does not
document a standalone `validateDefinition` operation; no fictitious endpoint is
called. These checks are **not** semantic validation of a clinical rule.
Production promotion still requires the supported validation workflow available
in your Fabric tenant, UI review, approval, and synthetic end-to-end tests.

Test no-match, boundary, match, duplicate, delayed, and malformed events in Fabric
and at the destination. This service's unit tests do not emulate Activator rule
evaluation. Exactly one final effect for duplicate events requires downstream
idempotency, not just a single observed callback.

Public API references:

* [Get Reflex definition](https://learn.microsoft.com/en-us/rest/api/fabric/reflex/items/get-reflex-definition)
* [Update Reflex definition](https://learn.microsoft.com/en-us/rest/api/fabric/reflex/items/update-reflex-definition)
* [Long-running operation result](https://learn.microsoft.com/en-us/rest/api/fabric/core/long-running-operations/get-operation-result)

The implementation uses the documented `/v1/workspaces/{workspaceId}/reflexes/{id}`
endpoints. Fabric token scope is `https://api.fabric.microsoft.com/.default`.
Grant the identity the required workspace/item access and tenant permission for
service principals/managed identities; Azure subscription RBAC alone is not
Fabric workspace authorization.

## Docker

```powershell
Copy-Item .env.example .env   # Only if you have not created it yet
# Populate .env first.
docker compose up --build -d
docker compose logs -f app
docker compose down
```

The image builds/tests the app in a Maven stage, runs a JRE as UID 10001, and does
not contain `.env`. Compose supplies settings at runtime and persists audit data
in a named volume. Removing that volume destroys rollback/audit history. With
Docker, host Azure CLI credentials are not automatically available: use a local
service principal, or another explicitly configured identity. Do not bake tokens
or a developer's credential cache into an image.

## Azure Service Bus and AKS

1. Provision the namespace/queue using [the Bicep template](infra/servicebus.bicep):

   ```powershell
   az deployment group create --resource-group <resource-group> `
     --template-file .\infra\servicebus.bicep `
     --parameters namespaceName=<globally-unique-namespace>
   ```

   Queue duplicate detection is a creation-time setting. Check the configuration
   of any existing queue explicitly; the app does not silently create queues.
   Use Premium/private networking and your organization's recovery policy where
   required. This template is a Standard-tier POC, not a regional DR design.

2. Enable AKS OIDC and Workload Identity; create a user-assigned managed identity
   and federate it with subject
   `system:serviceaccount:fabric-activator:fabric-activator` and audience
   `api://AzureADTokenExchange`. Assign that identity **Azure Service Bus Data
   Sender** and **Azure Service Bus Data Receiver** scoped to the queue/namespace.
   Grant Fabric access separately. Configure ACR pull access for the cluster.

3. Build and push an image for the **cluster's** CPU architecture, not necessarily
   your development machine's architecture:

   ```powershell
   az acr login --name <registry>
   docker buildx build --platform linux/amd64 `
     -t <registry>.azurecr.io/fabric-rt-actuator:0.1.0 --push .
   ```

4. Edit [the AKS manifest](deploy/aks.yaml): image, managed-identity client ID,
   namespace, downstream URL, and Fabric IDs. Create the namespace and the runtime
   Secret first. For example, use a temporary local env file with only
   `WEBHOOK_API_KEY`, `ADMIN_API_KEY`, and optional `DOWNSTREAM_BEARER_TOKEN`:

   ```powershell
   kubectl create namespace fabric-activator
   kubectl -n fabric-activator create secret generic fabric-activator-secrets `
     --from-env-file=<private-secrets-env-file>
   kubectl apply -f .\deploy\aks.yaml
   kubectl -n fabric-activator rollout status deployment/fabric-activator
   ```

   Do not commit that file. Prefer Azure Key Vault CSI / an approved secret
   delivery system in production. Do not put Azure client secrets into this
   workload-identity deployment.

5. Configure TLS, DNS, and an ingress controller. The optional
   [ingress example](deploy/ingress.example.yaml) exposes **only** the exact
   webhook path. Replace the example host, ingress class and TLS Secret; enforce
   HTTPS, caller restrictions/rate limits, and relevant network policies in your
   gateway. Do not expose `/api/rules/*` or port 8081 publicly.
   The service is ClusterIP; internal management can be accessed through:

   ```powershell
   kubectl -n fabric-activator port-forward service/fabric-activator 8080:8080
   ```

6. Verify real broker enqueue/redelivery/DLQ and a synthetic Fabric-triggered
   action before production. Mount/backup the audit PVC and monitor capacity;
   data is sensitive, not automatically pruned, and not a tamper-proof audit
   store. Apply retention and access policies. Restrict internal access to
   management APIs and health endpoints according to your cluster policy.

The manifest uses a persistent Azure Disk, non-root execution, read-only root
filesystem, resource limits, graceful termination, and separate health probes.
`GET :8081/actuator/health/liveness` and `/readiness` do not expose detailed state.
Recreate rollout incurs a short intake outage; the adapter must retry. A worker
termination can redeliver in-flight messages, which is safe only with downstream
idempotency.

## Verification

```powershell
mvn verify
docker compose --env-file .env.example config --no-env-resolution --quiet
```

Tests cover enqueue acknowledgment/failure, payload validation and limits, API-key
separation, downstream status/retry/dead-letter behavior, stable delivery keys,
string/object rule instances, graph preservation, ambiguous-condition rejection,
Fabric synchronous/asynchronous API flows, operation-URL trust, deployment
snapshots, stale checksums, dry runs, failure auditing, and rollback.

Live Azure connectivity, AKS ingress/TLS, Fabric permissions, and clinical rule
evaluation require environment-specific integration testing.
