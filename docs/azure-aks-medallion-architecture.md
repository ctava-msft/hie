# Azure-Native AKS Medallion Architecture

## Purpose

This document proposes an Azure-native replacement for the Microsoft Fabric
Real-Time Intelligence, Eventstream, workspace, Eventhouse, and Activator
implementation described by the HIE architecture.

The target retains the existing logical boundaries:

```text
Epic / source systems
  -> Event Hubs
  -> AKS ingress and Bronze writer
  -> Bronze in Azure Data Lake Storage Gen2
  -> Service Bus normalization queue
  -> AKS HL7/FHIR normalization
  -> Silver in Azure Data Lake Storage Gen2
  -> Service Bus projection queue
  -> AKS Gold projector
  -> Azure Data Explorer (Eventhouse equivalent)
  -> dashboards, KQL, rules, FHIR/API services, and analytical consumers
```

The proposal is a replacement for Fabric's data plane, not a replacement for
the source interface or consumer contracts. Existing AKS-hosted API services,
API Management, Entra ID, monitoring, and downstream integrations can be
retained or adapted.

## Summary

Use Azure Kubernetes Service (AKS) for custom ingestion, transformation,
validation, projection, and operational processing. Place a durable queue
between each independently recoverable processing stage. Store immutable raw
events in Bronze, validated normalized records in Silver, and operational
query projections in a KQL-compatible Azure Data Explorer (ADX) database that
serves as the Eventhouse/Gold equivalent.

The recommended messaging pattern uses:

- **Azure Event Hubs** for high-throughput source ingress and short-term stream
  replay.
- **Azure Service Bus** for durable work distribution, poison-message
  handling, delayed delivery, dead-letter queues, and stage-specific queues or
  topics.
- **KEDA on AKS** to scale queue-processing deployments from zero or from a
  small steady-state replica count based on queue depth.

Event Hubs and Service Bus solve different problems in this design. Event
Hubs should not be treated as a work queue, and Service Bus should not replace
Event Hubs for an Epic integration expected to sustain a very high event rate.
Both are useful: Event Hubs buffers the incoming stream, while Service Bus
coordinates recoverable processing steps.

## Service Mapping

| Fabric capability | Proposed Azure-native equivalent | Notes |
|---|---|---|
| Fabric workspace | Azure resource groups, subscriptions, Entra groups, and Git-managed deployment boundaries | Separate production data planes by environment and, if required, by medallion layer. |
| Eventstream | Event Hubs plus AKS consumers or a managed ingestion adapter | AKS owns the contract translation and checkpoint visibility. |
| Bronze Eventhouse | ADLS Gen2 raw zone | Immutable, replayable source representation partitioned by source, event date, and entity or tenant. |
| Bronze/Silver orchestration | Service Bus topics or queues | Durable stage handoffs with dead-lettering and independently scaled consumers. |
| Fabric transformation | AKS normalization services | Decode, parse, validate, map terminology, and publish normalized records. |
| Silver Eventhouse | ADLS Gen2 normalized zone, preferably Delta Lake | Versioned, queryable, idempotently materialized normalized data. |
| Eventhouse / Gold | Azure Data Explorer | Near-real-time KQL, dashboards, retention, and time-series or event analytics. |
| Real-Time Dashboard | Azure Data Explorer Dashboards or Azure Managed Grafana | Select based on audience and operating model. |
| Activator | AKS rule processor, Azure Functions, or Azure Stream Analytics where appropriate | Service Bus carries resulting actions durably to approved delivery systems. |
| Fabric notebook/materialization | AKS Data API jobs, Databricks, or scheduled Delta jobs | Choose only if durable batch or complex historical transformation is required. |
| Fabric Lakehouse / OneLake | ADLS Gen2 plus Delta Lake | Analytical data remains in Azure storage under the organization's control. |
| Power BI semantic layer | Power BI over ADX and/or ADLS/Delta | Preserve governed semantic models and row/object-level security. |
| APIM and HAPI FHIR | APIM and AKS services | Retain the current consumer boundary; query ADX or another approved read model. |

## Logical Architecture

```text
                              Azure Monitor / Log Analytics
                                      ^ metrics and traces
                                      |
Epic / interface engines ----------+ |
  FHIR / HL7 / JSON                 | |
                                  v |
                       +------------------------+
                       | Azure Event Hubs       |
                       | partitioned ingress    |
                       +-----------+------------+
                                   | checkpointed consumer
                                   v
                       +------------------------+
              AKS      | Bronze Ingest Service  |
                       +-----------+------------+
                                   | raw blob + manifest
                                   v
                       +------------------------+
                       | ADLS Bronze            |
                       | original payload       |
                       +-----------+------------+
                                   | publish after commit
                                   v
                       +------------------------+
                       | Service Bus Topic      |
                       | bronze-ready / DLQ     |
                       +-----+-------------+----+
                             |             |
                             v             v
                    +----------------+   other subscriptions
             AKS    | Normalizer     |
                    +-------+--------+
                            | validated normalized records
                            v
                    +----------------+
                    | ADLS Silver    |
                    | Delta/Parquet  |
                    +-------+--------+
                            | publish after commit
                            v
                    +------------------------+
                    | Service Bus Topic      |
                    | silver-ready / DLQ     |
                    +-----------+------------+
                                v
                    +------------------------+
             AKS    | Gold Projector / Rules |
                    +-----------+------------+
                                |
                 +--------------+----------------+
                 v                               v
       +-------------------+            +----------------------+
       | Azure Data Explorer|           | Action Outbox / SB   |
       | Eventhouse / Gold  |           | durable notifications|
       +---------+---------+            +----------+-----------+
                 |                                 |
        +--------+---------+                       v
        |                  |              approved downstreams
        v                  v
   KQL dashboards   AKS APIs -> APIM
```

## Component Responsibilities

### Azure Event Hubs

Event Hubs is the external ingestion boundary. It:

- decouples Epic and other publishers from downstream processing;
- partitions events for parallel consumption;
- retains events for a bounded replay window;
- captures event time, source event ID, correlation ID, partition, sequence
  number, and schema version in the event envelope; and
- supports an ordered replay within a partition.

Choose a partition key, such as source system plus message or patient context,
only after throughput and ordering requirements are measured. A partition key
must distribute load; it must not become a single-patient or single-source
hotspot.

### AKS Bronze Ingest Service

The Bronze service consumes Event Hubs using a checkpoint store. It writes the
unaltered encoded payload and a small metadata manifest to ADLS Gen2. It must
not reject an event merely because the clinical payload cannot yet be parsed.

The Bronze commit includes:

- source payload unchanged;
- source envelope;
- payload hash;
- source event ID and correlation ID;
- Event Hubs partition, sequence, and enqueue time;
- ingestion time;
- payload size and schema/envelope version; and
- optional tenant or interface identifier.

After the ADLS write is committed, the service publishes a small
`bronze-ready` message containing the blob URI and identifying metadata. The
message should not contain the full clinical payload.

### Azure Service Bus

Service Bus coordinates processing rather than serving as the Bronze store.
Recommended entities are:

1. `bronze-ready`: normalize committed raw events.
2. `silver-ready`: project committed normalized records and evaluate rules.
3. `actions`: deliver approved alerts or workflow actions.
4. One dead-letter queue for each work entity's failed messages.

Use topics when more than one independent subscriber needs the same stage
event. Use queues when a stage has one consumer group. Enable duplicate
detection for the maximum useful window, set a finite delivery count, and
require dead-letter reason metadata that does not expose PHI.

Service Bus sessions can preserve order by patient, encounter, or another
approved key where processing order is required. They reduce parallelism and
should be used only for workflows that truly require ordering. Idempotency
must still be implemented because Service Bus provides at-least-once delivery.

### AKS Normalization Service

The normalization deployment:

1. receives the Bronze manifest from Service Bus;
2. reads the exact Bronze blob;
3. validates the envelope and source;
4. decodes Base64 where applicable;
5. parses HL7 or FHIR according to a versioned parser;
6. validates mandatory fields and supported message types;
7. maps approved fields into the versioned canonical Silver model;
8. records validation and terminology results;
9. writes the normalized object and metadata to Silver; and
10. completes the Service Bus message only after the Silver commit succeeds.

Supported FHIR resources and HL7 versions should be allow-listed. Unknown but
valid message types belong in a quarantine or unsupported path with a clear
operator action, not in the live clinical projections.

### Azure Data Lake Storage Gen2

ADLS Gen2 provides the durable medallion store.

#### Bronze

- immutable, append-only source representation;
- partition by source, event date, and optionally interface;
- retain sufficient lineage for audit and controlled replay;
- protect with private endpoints, versioning, soft delete, lifecycle policy,
  and least-privilege access; and
- do not expose directly to broad analytical or application identities.

#### Silver

- one versioned normalized representation per accepted source event;
- include source event ID, Bronze URI, parser/mapping version, event time,
  ingestion time, validation status, and terminology version;
- prefer Delta tables when update, merge, schema evolution, and historical
  query requirements justify them;
- use encrypted storage and limit direct access to processing and governed
  analytical identities; and
- retain unsupported, invalid, or partially mapped events separately with a
  sanitized reason code.

#### Data lifecycle

Retention for Bronze and Silver should be driven by clinical records,
litigation, privacy, and regional requirements. Lifecycle tiering can reduce
cost, but expiry and rehydration must preserve replay commitments. Do not use a
single generic retention value without data-governance approval.

### AKS Gold Projector

The Gold projector materializes the approved operational projections into ADX.
Examples include patient, encounter, observation, event status, data-quality,
and monitoring views. It:

- reads only committed Silver records;
- applies versioned business and clinical projection rules;
- maintains a stable ADX event ID for idempotent ingestion;
- writes updates as append-only event records unless a use case explicitly
  requires state materialization;
- publishes rule/action candidates to a durable action path; and
- exposes checkpoint, lag, and projection-failure metrics.

ADX can ingest through queued ingestion, an SDK, Event Hubs, or a broker
adapter. For this design, the AKS projector should own projection semantics
and use ADX queued ingestion for resilient, batched writes. ADX ingestion
success is not the same as message processing success; the projector needs a
tracked ingestion operation or a reconciliation mechanism.

### Azure Data Explorer: Eventhouse Equivalent

ADX replaces Fabric Eventhouse for KQL-based operational and event analytics.
The ADX database contains:

- append-only canonical event tables;
- functions or materialized views for current-state projections;
- retention and cache policies by table;
- row-level security or database/view restrictions where supported by the
  approved access pattern;
- ingestion failure and latency monitoring; and
- dashboards for clinical operations, interface health, and reconciliation.

Use update policies only for deterministic, low-cost projections. Put complex
or versioned business transformation in the AKS Gold projector so behavior is
testable in application code and can be rolled back independently.

ADX is the Eventhouse-equivalent serving layer, not a general clinical system
of record. Access should be mediated for external applications by an API
service and API Management rather than granting broad direct ADX permissions.

### AKS Rule and Action Processing

Rules should be evaluated against governed Gold data or selected events.
Detection and action delivery are separate stages:

1. a rule evaluator emits an action intent;
2. the intent is persisted in an outbox or dedicated action table;
3. a Service Bus publisher delivers the intent after durable commit;
4. an action worker performs the approved integration with bounded retries;
5. failed deliveries move to a dead-letter queue; and
6. operators re-drive only after reviewing the failure and its clinical
   significance.

This avoids assuming that an alerting engine is a durable clinical delivery
system. Rules require explicit ownership, versioning, approval, audit, rate
limits, test fixtures, and a safe disable path.

### Kubernetes Hosting

Recommended AKS workloads:

- `bronze-ingest`: Event Hubs consumer and raw writer;
- `normalizer`: Bronze-to-Silver worker;
- `gold-projector`: Silver-to-ADX worker;
- `rule-evaluator`: approved detections, if not implemented in another engine;
- `action-worker`: Service Bus action consumer;
- `reconciler`: cross-stage counts and replay orchestration; and
- API workloads, including HAPI FHIR or other read adapters.

Use separate deployments for scaling and operational independence. Run
Kubernetes Jobs for controlled historical reprocessing rather than coupling
replay to the steady-state consumers.

## AKS Autoscaling

Use KEDA scalers for:

- Service Bus queue depth and age;
- Event Hubs lag, where the selected autoscaling metric is accurate; and
- optionally scheduled minimum capacity for predictable clinical windows.

Apply:

- minimum and maximum replica limits;
- CPU and memory requests/limits;
- pod disruption budgets;
- node pools separated by workload class where justified;
- spot nodes only for interruption-tolerant workloads; and
- backpressure when ADLS, ADX, Service Bus, or a downstream API is throttled.

Autoscaling does not replace load testing. Maximum service bus concurrency,
Event Hubs partition count, ADLS write patterns, ADX ingestion capacity, and
downstream limits should be sized from measured peak rate and recovery
requirements.

## Delivery Semantics and Idempotency

The platform should assume **at-least-once** delivery end to end.

Every stage derives or carries a stable event identity:

```text
eventKey = sourceSystem + sourceEventId + messageType
```

If the source cannot provide a trustworthy event ID, derive one from an
approved canonical hash over immutable envelope fields. Never hash mutable
receive timestamps or processing attempts.

Each stage must:

- detect repeated event keys;
- avoid duplicate current-state effects;
- preserve the original payload and lineage;
- record whether the repeat was ignored, replaced, or rejected;
- distinguish a redelivered message from a new clinical version; and
- make downstream requests idempotent where an endpoint supports an
  idempotency key.

Storage and queue publication cannot be treated as one transaction. The safe
pattern is a durable stage record plus an outbox or equivalent publisher. A
background publisher retries unsent records, and a reconciler detects gaps
between stored records and enqueued work.

## Failure Handling

| Failure | Expected behavior |
|---|---|
| Event Hubs unavailable | Publisher retries with source controls; AKS resumes from its last checkpoint. |
| Bronze write fails | Do not checkpoint or publish `bronze-ready`; retry until transient capacity/storage recovers. |
| Normalization fails permanently | Preserve Bronze, record a sanitized error, dead-letter the queue message, and route to quarantine. |
| Silver write fails | Do not complete the queue message; retry with the same event identity. |
| Service Bus publish fails after storage commit | Outbox/publisher retries or reconciler republishes the committed stage event. |
| ADX throttles | Retry according to ingestion status and back off; do not create duplicate Gold effects. |
| ADX unavailable | Queue/checkpoint remains pending; calculate backlog and recovery time before scaling. |
| Downstream action fails | Retry only approved transient failures; dead-letter permanent or policy-related failures. |
| Malformed or unsupported payload | Keep raw Bronze lineage, route to quarantine, and alert on volume or trend. |

## Security Architecture

- Use Microsoft Entra workload identity for AKS; do not deploy service
  principal secrets or Service Bus/Event Hubs connection strings.
- Grant each workload only the role it needs for its Event Hubs entity, storage
  container/path, Service Bus entity, and ADX database/table.
- Use private endpoints and private DNS for storage, Service Bus, Event Hubs,
  ADX, Key Vault, Container Registry, and AKS API access where the selected
  service and network topology require them.
- Disable local authentication for broker and storage resources unless a
  documented exception requires it.
- Use Azure Key Vault with workload identity only for configuration that cannot
  use native managed identity.
- Apply Pod Security Admission, restricted network policies, signed and
  vulnerability-scanned images, digest-pinned deployment images, and private
  ACR integration.
- Keep PHI out of logs, queue names, Kubernetes object names, telemetry
  dimensions, exception messages, and dead-letter reason fields.
- Encrypt at rest and in transit; use customer-managed keys only when required
  and after validating service compatibility and operations.
- Separate operators, deployers, replay authorities, clinical data readers,
  and audit readers with Entra groups and Azure RBAC.

## Observability and Operations

Create one end-to-end correlation trail from ingress to action without placing
PHI in telemetry:

- event key or opaque correlation ID;
- Bronze blob commit time;
- queue enqueue and receive times;
- normalization and mapping version;
- Silver commit time;
- ADX ingestion operation and event commit time;
- action enqueue, delivery, retry, and terminal outcome; and
- workload release version and replica identity where useful.

Required operational measures include:

- Event Hubs ingress rate, publish errors, and consumer lag;
- oldest Bronze, normalization, and projection message age;
- Service Bus active/dead-letter counts and delivery attempts;
- parse, validation, quarantine, and mapping failure rates;
- Bronze/Silver/Gold counts by reconciliation window;
- ADX ingestion latency, failures, throttling, query latency, and health;
- AKS restarts, saturation, pod readiness, and KEDA scaling events;
- FHIR/API latency, error rate, and authorization denials; and
- rule evaluation, action delivery, suppression, and operator action audit.

Alert on symptoms with ownership and runbooks. Queue depth alone is not enough:
measure backlog age and compare it to the agreed recovery objective.

## Resilience and Regional Recovery

Before production selection, set measurable recovery objectives for each stage.
Recommended options include:

- paired or active-secondary Event Hubs where source publishing can support it;
- Service Bus namespace replication or a documented cutover mechanism;
- ADLS geo-redundant storage and an approved second-region recovery process;
- a second ADX cluster/database strategy based on RTO/RPO and cost;
- AKS deployed with availability zones and a separate recovery cluster
  definition; and
- immutable Bronze retention as the reconstruction source.

Cross-region failover must address duplicate detection, partition and sequence
continuity, replay starting points, rule/action suppression, and consumer
switch authority. Deploying duplicate regional resources alone is not a tested
recovery architecture.

## Cost Considerations

The major cost drivers are:

- AKS node pools and reserved/elastic capacity;
- Event Hubs throughput units or processing units and retention;
- Service Bus premium capacity and message operations;
- ADLS capacity, transactions, lifecycle tiering, and transfer;
- ADX compute and storage SKU, cache, and ingestion;
- Log Analytics ingestion and retention;
- private endpoints, NAT/firewall egress, and network transfer; and
- API Management and monitoring services.

Service Bus Premium is appropriate where predictable network isolation,
capacity, and production workload requirements apply. For a lower-volume POC,
Standard tier may be sufficient if its networking and feature limitations are
accepted, but the production decision must be explicit.

## Proposed Deployment Boundaries

| Boundary | Recommended contents |
|---|---|
| Shared platform | networking, private DNS, AKS, ACR, Key Vault, monitoring, policy |
| Ingress | Event Hubs, source identities, capture/checkpoint resources |
| Data platform | ADLS Bronze/Silver, ADX, lifecycle and data permissions |
| Processing | Service Bus, AKS workloads, KEDA, deployment identities |
| Serving | HAPI FHIR/API adapters, APIM, dashboards, consumer identities |
| Operations | alerts, runbooks, reconciliation reports, replay governance |

Use separate subscriptions or resource groups only where isolation, billing,
quotas, regulatory requirements, or operational ownership justify them. Avoid
one resource group per logical service without a management reason.

## Migration Plan

### Phase 0: Contract and baseline

- Inventory Fabric workspaces, Eventstreams, Eventhouse databases/tables,
  Activator rules, notebooks, identities, and consumers.
- Capture current event size, peak throughput, latency, retention, failure,
  replay, and query requirements.
- Freeze and version the event envelope and Bronze-to-Silver mappings.
- Identify which Fabric behavior is required versus incidental.

### Phase 1: Parallel Bronze

- Deploy Event Hubs, ADLS Bronze, Service Bus, and the AKS Bronze ingester.
- Publish a controlled duplicate or test stream to Azure and Fabric.
- Prove payload equality, metadata lineage, ordering assumptions, and
  reconciliation before enabling normalization.

### Phase 2: Parallel Silver and Gold

- Run AKS normalization and the Gold projector in parallel with Fabric.
- Reconcile record counts, values, validation outcomes, and current-state
  projections.
- Validate every ADX KQL consumer against the new schemas and functions.
- Build quarantine, dead-letter, replay, and rollback runbooks.

### Phase 3: Consumer cutover

- Move dashboards to ADX or Managed Grafana after query acceptance.
- Cut API and HAPI read paths to the approved Azure read model through APIM.
- Migrate rule/action paths with delivery disabled, then enable by approved
  rule cohort.
- Run rollback-capable dual reads and compare query results during a bounded
  observation window.

### Phase 4: Fabric retirement

- Stop Fabric producers only after reconciliation and consumer acceptance.
- Retain source and transformed data for the approved transition period.
- Remove Fabric identities, gateways, capacities, and workspaces using a
  dependency-verified retirement checklist.
- Preserve evidence for clinical, security, and operational sign-off.

## POC Acceptance Criteria

1. **Losslessness:** Bronze payload hashes match the source stream; metadata
   permits deterministic replay.
2. **Durability:** simulated worker and node failures do not require republishing
   completed source events.
3. **Idempotency:** replaying an event does not create duplicate Silver or
   current-state Gold effects.
4. **Poison handling:** malformed data is preserved, quarantined, visible, and
   does not block healthy events.
5. **Recovery:** backlog recovery meets the agreed throughput and lag target.
6. **Freshness:** p50/p95 source-to-ADX and source-to-API latency meet the
   approved SLOs.
7. **Compatibility:** representative KQL queries and consumers produce expected
   results against ADX.
8. **Security:** unauthorized identities cannot read raw payloads, publish to
   broker entities, query clinical projections, or invoke replay.
9. **Operations:** dashboards, alerts, owners, dead-letter re-drive steps, and
   rollback procedures are exercised.
10. **Clinical and data governance:** supported message types, mappings,
    terminology versions, retention, and access policies are approved.

## Decisions Required

The following decisions should be made before implementation:

1. **Stream versus queue boundary:** validate whether Event Hubs receives all
   external events or whether selected sources can publish directly to
   Service Bus.
2. **Silver format:** choose Delta Lake when merge/schema/history requirements
   justify a runtime dependency; otherwise use versioned Parquet/JSON plus a
   catalog.
3. **Gold engine:** confirm ADX as the Eventhouse replacement and define required
   dashboards, retention, cache, and query concurrency.
4. **State model:** define append-only event projections versus updateable
   current-state records.
5. **Ordering:** identify workflows requiring per-key order and the associated
   throughput trade-off of Service Bus sessions.
6. **Rule engine:** use an AKS service, Azure Functions, Azure Stream Analytics,
   or an approved external rules platform based on complexity and ownership.
7. **Retention and privacy:** approve Bronze, Silver, queue, log, ADX, and
   dead-letter retention.
8. **Availability:** establish RTO/RPO and the multi-region recovery strategy.
9. **Replay authority:** define who may replay which windows and how actions are
   suppressed or deduplicated during replay.
10. **Existing consumers:** inventory every direct KQL, Power BI, notebook,
    Activator, API, or OneLake dependency before removing Fabric.

## Platform Comparison

| Dimension | AKS-based real time | Microsoft Fabric Real-Time Intelligence | Azure Databricks real time / Lakeflow |
|---|---|---|---|
| **Best fit** | Custom, high-control streaming and integration workloads | Fast delivery of operational real-time analytics with minimal platform management | Lakehouse-centric ingestion, transformation, machine learning, and analytics |
| **Architecture** | Event Hubs + AKS/KEDA + Service Bus + ADLS + ADX | Event Hubs + Eventstream + Eventhouse + Activator + OneLake | Kafka/Event Hubs + Structured Streaming/Lakeflow + Delta Lake + SQL Warehouse |
| **Primary strength** | Maximum flexibility and portability | Low-code, integrated real-time experience | Unified batch, streaming, Delta, governance, and data engineering |
| **Pros** | Full control over parsers, retries, ordering, APIs, and replay; any language or framework; KEDA scaling; clear stage boundaries; reusable AKS skills | Rapid implementation; managed Eventstream, KQL Eventhouse, dashboards, and Activator; strong Power BI/OneLake integration; less infrastructure management | Mature streaming transformations; strong exactly-once Delta writes; ACID Bronze/Silver/Gold lakehouse; batch/stream convergence; notebooks, Lakeflow, Unity Catalog, ML, and quality controls |
| **Cons** | Highest operational burden; container, broker, cluster, networking, security, SRE, and upgrade ownership; idempotency and recovery must be engineered; larger deployment and testing surface | Proprietary deployment model; less control over internal processing and recovery; capacity economics and throttling; specialized permissions and API automation; Eventhouse is not a transactional store; Activator is not a complete durable delivery engine | Spark and streaming expertise required; cluster/job tuning remains; Event Hubs or Service Bus may still be needed; less natural for low-latency application APIs or durable work queues; continuous compute can be costly |
| **Operational complexity** | **High** | **Low–Medium** | **Medium–High** |
| **Implementation speed** | **Slower** | **Fastest for supported patterns** | **Medium** |
| **Customization** | **Highest** | **Medium–Low** | **High**, within the Spark/Databricks ecosystem |
| **Low-latency operational queries** | Excellent with ADX or another serving store | Excellent with Eventhouse/KQL | Good for many use cases, but ADX or another serving layer may be preferable for stringent event-query workloads |
| **Bronze/Silver/Gold support** | Explicitly custom-built | Native conceptual flow, but architecture-dependent | **Strongest native lakehouse alignment**, especially with Delta Live Tables/Lakeflow |
| **Replay and correction** | Fully controllable, but requires engineering | Managed patterns with platform-specific operational details | Strong checkpoints, Delta history, and recovery; replay still requires explicit design |
| **Durable action delivery** | Strong with Service Bus, outbox, and DLQ patterns | Requires an external durable delivery path behind Activator | Supported, but often best paired with Service Bus, Functions, or AKS |
| **Governance** | Azure RBAC, Entra, network controls, Key Vault, ADLS, and custom lineage | Centralized through Fabric workspaces, OneLake, and tenant administration | Strong Unity Catalog, Delta lineage, access policies, and audit |
| **Engineering skills** | Kubernetes, messaging, ADX, ADLS, application development, and SRE | KQL, Fabric, Eventstream, Power BI, and workspace administration | Spark, Python/Scala/SQL, Delta Lake, Databricks workflows, and Unity Catalog |
| **Portability / exit strategy** | High at the application layer; Azure managed-service dependencies remain | Lower; workload definitions and data-plane behavior are Fabric-specific | Moderate; Spark and Delta are portable, while Databricks tooling remains platform-specific |
| **Cost profile** | AKS, Event Hubs, Service Bus, ADLS, ADX, monitoring, and APIM; combined fixed and variable costs | Fabric capacity plus supporting Azure ingress/services; efficient when capacity is well utilized | Databricks DBUs, compute, storage, and Azure services; continuous streaming compute can be expensive |
| **Healthcare / PHI controls** | Strong control over networking, identity, logging, and processing boundaries, but more controls must be implemented and maintained | Strong platform controls; regional, private-access, tenant, and capacity requirements must be validated | Strong governance and identity controls; notebook, job, log, and private-connectivity behavior must be validated for PHI |
| **Key failure modes** | Pod eviction, checkpoint loss, duplicate processing, broker throttling, DLQ, partial ADLS commits, and ADX ingestion failure | Capacity throttling, update-policy failures, tenant permissions, workspace dependencies, and action delivery limits | Checkpoint mismatch, schema drift, small files, cluster restart, streaming lag, and downstream serving limitations |

### Selection Guidance

- **Choose AKS** when custom HL7/FHIR handling, API behavior, clinical rules,
  durable delivery, infrastructure control, or portability is the primary
  requirement.
- **Choose Fabric Real-Time Intelligence** when the goal is rapid KQL-based
  operational analytics, dashboards, Power BI, and governed real-time
  experiences with the least infrastructure engineering.
- **Choose Azure Databricks** when data-engineering transformation, batch and
  stream convergence, historical analytics, machine learning, Unity Catalog,
  and Delta Lake governance are the center of the solution.

For an HIE, a hybrid may be appropriate:

```text
Event Hubs
  -> Databricks/Lakeflow for scalable medallion data engineering
  -> Delta Lake Silver
  -> Azure Data Explorer for low-latency operational Gold and KQL
  -> AKS for APIs, FHIR access, custom rules, and delivery services
  -> Service Bus for actions, retries, and dead-letter handling
```

Fabric remains the fastest proof-of-concept path, Databricks is strongest for
an analytics-first lakehouse, and AKS is the most direct fit when custom
real-time processing and durable integration behavior are the dominant needs.

## Recommendation

Proceed with a focused POC using:

- Event Hubs for source ingress;
- Service Bus topics and dead-letter queues for durable stage handoffs;
- KEDA-scaled AKS consumers for Bronze ingestion, normalization, projection, and
  actions;
- ADLS Gen2 for immutable Bronze and versioned Silver;
- ADX for the Eventhouse/Gold KQL serving layer; and
- APIM in front of AKS-hosted read and FHIR API services.

This preserves the useful medallion semantics while making application
behavior, queue ownership, replay, deployment, and operations explicit in
Azure. Fabric features should be removed only after data equivalence,
consumer compatibility, reliability, and governance controls have been proven
in parallel operation.
