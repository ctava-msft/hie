"""Synthetic ePNA R4 fixtures, Eventstream publisher and governed FHIR smoke test."""

import argparse
import json
import logging
import os
from pathlib import Path
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "samples" / "epna"
BASE = "https://example.org/fhir"
DEMO_SYSTEM = BASE + "/CodeSystem/epna-demo"
ORIGIN = {"system": BASE + "/CodeSystem/data-origin", "code": "synthetic"}
TYPES = {"Patient", "Encounter", "Condition", "Observation", "ServiceRequest", "DiagnosticReport", "Provenance"}
PHASES = ("admission", "qualification", "monitoring", "discharge", "transfer")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = build_opener(NoRedirect())


def instant(value):
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def coding(system, code, display=None):
    result = {"system": system, "code": code}
    if display:
        result["display"] = display
    return {"coding": [result]}


def reference(resource_type, resource_id):
    return {"reference": f"{resource_type}/{resource_id}"}


def resource(resource_type, resource_id, stamp, version=1, **fields):
    return {
        "resourceType": resource_type,
        "id": resource_id,
        "meta": {"versionId": str(version), "lastUpdated": stamp, "tag": [dict(ORIGIN)]},
        **fields,
    }


def patient(number, stamp):
    return resource(
        "Patient", f"epna-p{number}", stamp, active=True,
        identifier=[{"system": BASE + "/identifier/demo-xmrn", "value": f"SYNTHETIC-{number:03}"}],
        name=[{"use": "official", "family": "Synthetic", "given": [f"Demo{number}"]}],
        gender="unknown", birthDate="1970-01-01",
    )


def encounter(number, stamp, start, status="in-progress", encounter_class="EMER", version=1):
    period = {"start": start}
    if status == "finished":
        period["end"] = stamp
    return resource(
        "Encounter", f"epna-e{number}", stamp, version, status=status,
        **{"class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": encounter_class}},
        subject=reference("Patient", f"epna-p{number}"), period=period,
        location=[{"location": {"identifier": {"system": BASE + "/identifier/facility", "value": "SYNTHETIC-ED-01"}}}],
        reasonCode=[coding(DEMO_SYSTEM, "demo-ed-evaluation", "Synthetic emergency evaluation")],
    )


def monitoring(number, state, stamp, version):
    return resource(
        "Observation", f"epna-monitoring-{number}", stamp, version, status="final",
        code=coding(DEMO_SYSTEM, "monitoring-status", "Synthetic ePNA monitoring state"),
        subject=reference("Patient", f"epna-p{number}"), encounter=reference("Encounter", f"epna-e{number}"),
        effectiveDateTime=stamp, valueCodeableConcept=coding(DEMO_SYSTEM, state),
    )


def vital(resource_id, code, value, unit, ucum_code, stamp, version=1):
    return resource(
        "Observation", resource_id, stamp, version, status="final",
        category=[coding("http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs")],
        code=coding("http://loinc.org", code),
        subject=reference("Patient", "epna-p1"), encounter=reference("Encounter", "epna-e1"),
        effectiveDateTime=stamp,
        valueQuantity={"value": value, "unit": unit, "system": "http://unitsofmeasure.org", "code": ucum_code},
    )


def order(stamp, authored, status="active", version=1):
    return resource(
        "ServiceRequest", "epna-imaging-order", stamp, version, status=status, intent="order",
        code=coding(DEMO_SYSTEM, "chest-radiograph", "Synthetic chest imaging order"),
        subject=reference("Patient", "epna-p1"), encounter=reference("Encounter", "epna-e1"),
        authoredOn=authored, reasonReference=[reference("Condition", "epna-condition")],
    )


def signal(number, state, stamp):
    return {
        "AlertId": f"epna-alert-{number}-{state}",
        "EncounterId": f"epna-e{number}",
        "PatientReference": f"Patient/epna-p{number}",
        "FacilityId": "SYNTHETIC-ED-01",
        "EncounterStatus": "finished" if state == "discharged" else "in-progress",
        "EncounterClassCode": "IMP" if state == "transferred" else "EMER",
        "MonitoringStatus": state,
        "AlertEligible": state == "qualified",
        "AlertScore": None,
        "AlertReasonCode": f"fixture-{state}",
        "AlertReasonDisplay": "Synthetic workflow state only; no clinical scoring or treatment recommendation.",
        "SupportingResourceReferences": [f"Observation/epna-monitoring-{number}"],
        "QualificationPolicyVersion": "synthetic-fixture-v1-not-clinical",
        "EvaluatedAt": stamp,
        "CorrelationId": f"epna-demo-{number}",
    }


def provenance(phase, resources, stamp):
    return resource(
        "Provenance", f"epna-provenance-{phase}", stamp,
        target=[reference(item["resourceType"], item["id"]) for item in resources],
        recorded=stamp,
        agent=[{"who": {"identifier": {"system": BASE + "/identifier/test-agent", "value": "epna-fixture-generator"}}}],
        activity=coding(DEMO_SYSTEM, "synthetic-fixture"),
    )


def generate(directory, start):
    directory.mkdir(parents=True, exist_ok=True)
    times = [instant(start + timedelta(minutes=index)) for index in range(len(PHASES))]
    admission = []
    for number in range(1, 4):
        admission.extend([patient(number, times[0]), encounter(number, times[0], times[0]),
                          monitoring(number, "pending", times[0], 1)])
    condition = resource(
        "Condition", "epna-condition", times[1],
        clinicalStatus=coding("http://terminology.hl7.org/CodeSystem/condition-clinical", "active"),
        verificationStatus=coding("http://terminology.hl7.org/CodeSystem/condition-ver-status", "provisional"),
        category=[coding("http://terminology.hl7.org/CodeSystem/condition-category", "encounter-diagnosis")],
        code=coding(DEMO_SYSTEM, "pneumonia-evaluation", "Synthetic pneumonia evaluation, not a diagnosis"),
        subject=reference("Patient", "epna-p1"), encounter=reference("Encounter", "epna-e1"),
        onsetDateTime=times[0], recordedDate=times[1],
    )
    qualification = [
        condition, order(times[1], times[1]), vital("epna-spo2", "59408-5", 95, "%", "%", times[1]),
        monitoring(1, "qualified", times[1], 2), monitoring(2, "not-qualified", times[1], 2),
        monitoring(3, "qualified", times[1], 2),
    ]
    radiology = resource(
        "Observation", "epna-radiology", times[2], status="final",
        code=coding(DEMO_SYSTEM, "chest-radiograph-result"),
        subject=reference("Patient", "epna-p1"), encounter=reference("Encounter", "epna-e1"),
        effectiveDateTime=times[2], valueString="Synthetic radiology content for interoperability testing only.",
        basedOn=[reference("ServiceRequest", "epna-imaging-order")],
    )
    report = resource(
        "DiagnosticReport", "epna-report", times[2], status="final",
        code=coding(DEMO_SYSTEM, "chest-radiograph"),
        subject=reference("Patient", "epna-p1"), encounter=reference("Encounter", "epna-e1"),
        effectiveDateTime=times[2], issued=times[2],
        basedOn=[reference("ServiceRequest", "epna-imaging-order")],
        result=[reference("Observation", "epna-radiology")],
        conclusion="SYNTHETIC TEST DATA. No clinical interpretation or recommendation.",
    )
    monitoring_resources = [
        vital("epna-spo2", "59408-5", 94, "%", "%", times[2], 2),
        vital("epna-heart-rate", "8867-4", 82, "beats/minute", "/min", times[2]),
        vital("epna-respiratory-rate", "9279-1", 22, "breaths/minute", "/min", times[2]),
        vital("epna-temperature", "8310-5", 38.1, "degrees C", "Cel", times[2]),
        radiology, report, order(times[2], times[1], "completed", 2),
    ]
    stages = [
        (admission, [signal(n, "pending", times[0]) for n in range(1, 4)], []),
        (qualification, [signal(1, "qualified", times[1]), signal(2, "not-qualified", times[1]),
                         signal(3, "qualified", times[1])], ["epna-e1", "epna-e3"]),
        (monitoring_resources, [signal(1, "qualified", times[2]), signal(3, "qualified", times[2])], ["epna-e1", "epna-e3"]),
        ([encounter(1, times[3], times[0], "finished", version=2), monitoring(1, "discharged", times[3], 3)],
         [signal(1, "discharged", times[3])], ["epna-e3"]),
        ([encounter(3, times[4], times[0], encounter_class="IMP", version=2), monitoring(3, "transferred", times[4], 3)],
         [signal(3, "transferred", times[4])], []),
    ]
    manifest = {"syntheticOnly": True, "fhirVersion": "4.0.1", "phases": []}
    for index, (phase, (resources, signals, active)) in enumerate(zip(PHASES, stages)):
        stamp = times[index]
        resources.append(provenance(phase, resources, stamp))
        bundle = {
            "resourceType": "Bundle", "id": f"epna-{phase}", "type": "collection",
            "meta": {"tag": [dict(ORIGIN)]}, "timestamp": stamp,
            "entry": [{"fullUrl": f"{BASE}/{item['resourceType']}/{item['id']}", "resource": item} for item in resources],
        }
        events = []
        for item in resources:
            source_type = {
                "Observation": "ORU_R01", "DiagnosticReport": "ORU_R01",
                "ServiceRequest": "ORM_O01", "Condition": "ADT_A08", "Provenance": "SYNTHETIC",
            }.get(item["resourceType"], {"discharge": "ADT_A03", "transfer": "ADT_A02"}.get(phase, "ADT_A04"))
            events.append({
                "EventKind": "resource", "EventTime": stamp,
                "SourceEventId": f"{phase}-{item['resourceType']}-{item['id']}-v{item['meta']['versionId']}",
                "SourceMessageType": source_type, "Resource": item, "Signal": None,
            })
        for item in signals:
            events.append({
                "EventKind": "monitoring", "EventTime": stamp,
                "SourceEventId": f"{phase}-{item['AlertId']}", "SourceMessageType": "EPNA_DEMO_STATE",
                "Resource": None, "Signal": item,
            })
        stem = f"{index + 1:02}-{phase}"
        (directory / f"{stem}.bundle.json").write_text(json.dumps(bundle, indent=2) + "\n", encoding="utf-8")
        (directory / f"{stem}.events.ndjson").write_text(
            "".join(json.dumps(event, separators=(",", ":")) + "\n" for event in events), encoding="utf-8")
        manifest["phases"].append({
            "name": phase, "bundle": f"{stem}.bundle.json", "events": f"{stem}.events.ndjson",
            "expectedActiveEncounters": active,
        })
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    validate_samples(directory)
    print(f"Generated and structurally checked {len(PHASES)} synthetic R4 phases in {directory}.")


def references(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "reference":
                yield child
            else:
                yield from references(child)
    elif isinstance(value, list):
        for child in value:
            yield from references(child)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def validate_samples(directory):
    manifest = read_json(directory / "manifest.json")
    if manifest.get("syntheticOnly") is not True or manifest.get("fhirVersion") != "4.0.1":
        raise ValueError("Only the synthetic ePNA R4 fixture manifest is accepted.")
    if [phase["name"] for phase in manifest["phases"]] != list(PHASES):
        raise ValueError("Fixture phases must follow the complete ePNA lifecycle.")
    known = {}
    source_ids = set()
    for phase in manifest["phases"]:
        bundle = read_json(directory / phase["bundle"])
        if bundle.get("resourceType") != "Bundle" or bundle.get("type") != "collection":
            raise ValueError("The fixture must be a collection Bundle, not a FHIR write transaction.")
        items = [entry["resource"] for entry in bundle["entry"]]
        for item in items:
            key = f"{item['resourceType']}/{item['id']}"
            if item["resourceType"] not in TYPES or not re.fullmatch(r"epna-[A-Za-z0-9.-]+", item["id"]):
                raise ValueError("Only supported, explicitly synthetic ePNA resources can be published.")
            if ORIGIN not in item.get("meta", {}).get("tag", []):
                raise ValueError("Every resource must carry the synthetic data-origin tag.")
            if item["meta"]["lastUpdated"] != bundle["timestamp"]:
                raise ValueError("Resource version time must agree with the event time.")
            if key in known and int(item["meta"]["versionId"]) <= int(known[key]["meta"]["versionId"]):
                raise ValueError("Resource versions must increase across fixture phases.")
            known[key] = item
        for item in items:
            if any(ref not in known for ref in references(item)):
                raise ValueError("Fixture contains a dangling or out-of-scope FHIR reference.")
        events = [json.loads(line) for line in (directory / phase["events"]).read_text(encoding="utf-8").splitlines() if line]
        if [event["Resource"] for event in events if event["EventKind"] == "resource"] != items:
            raise ValueError("Eventstream resource payloads do not match the FHIR Bundle.")
        for event in events:
            if event["SourceEventId"] in source_ids or event["EventTime"] != bundle["timestamp"]:
                raise ValueError("Event identity or event-time contract is inconsistent.")
            source_ids.add(event["SourceEventId"])
            if event["EventKind"] == "monitoring":
                state = event["Signal"]
                if f"Encounter/{state['EncounterId']}" not in known or state["PatientReference"] not in known:
                    raise ValueError("Monitoring signal references an unknown patient or encounter.")
                if state["AlertEligible"] != (state["MonitoringStatus"] == "qualified"):
                    raise ValueError("Monitoring eligibility must match the explicit synthetic state.")
                if state["QualificationPolicyVersion"] != "synthetic-fixture-v1-not-clinical":
                    raise ValueError("A real clinical policy must not be published by this test harness.")
            elif event["EventKind"] != "resource":
                raise ValueError("Unsupported event kind.")
    return manifest


def publish(directory, phase_name, dry_run, confirmed):
    manifest = validate_samples(directory)
    phase = next(item for item in manifest["phases"] if item["name"] == phase_name)
    events = [line for line in (directory / phase["events"]).read_text(encoding="utf-8").splitlines() if line]
    if dry_run:
        print(f"Validated {len(events)} synthetic {phase_name} events; no network request was made.")
        return
    if not confirmed:
        raise ValueError("Publication requires --confirm-synthetic and a dedicated nonproduction Gold destination.")
    connection = os.environ.get("GOLD_EVENTSTREAM_CONNECTION_STRING")
    if not connection:
        raise ValueError("Set GOLD_EVENTSTREAM_CONNECTION_STRING from the custom endpoint's Event Hubs details.")
    from azure.core.exceptions import AzureError
    from azure.eventhub import EventData, EventHubProducerClient, TransportType

    logging.getLogger("azure").setLevel(logging.WARNING)
    try:
        with EventHubProducerClient.from_connection_string(
                connection, transport_type=TransportType.AmqpOverWebsocket, retry_total=3,
                logging_enable=False) as producer:
            batch = producer.create_batch(partition_key="epna-demo")
            for event in events:
                data = EventData(event)
                data.content_type = "application/json"
                batch.add(data)
            producer.send_batch(batch)
    except (AzureError, ValueError) as error:
        raise RuntimeError(
            f"Eventstream publication failed ({type(error).__name__}); check endpoint credentials, permissions and connectivity."
        ) from None
    print(f"Eventstream accepted {len(events)} {phase_name} events. Ingestion is asynchronous; run the smoke test.")


def get_fhir(url, token):
    headers = {"Accept": "application/fhir+json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        with HTTP.open(Request(url, headers=headers), timeout=30) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        body = error.read()
        try:
            return error.code, json.loads(body)
        except json.JSONDecodeError:
            return error.code, {"nonFhirError": True}
    except URLError as error:
        raise RuntimeError(f"FHIR endpoint is unreachable ({type(error.reason).__name__}).") from None


def smoke(directory, phase_name, base_url, timeout):
    parsed = urlparse(base_url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.path != "/fhir" or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("--base-url must be the HTTPS APIM base URL ending in /fhir.")
    token = os.environ.get("FHIR_ACCESS_TOKEN")
    if not token:
        raise ValueError("Set FHIR_ACCESS_TOKEN to an approved application's Fhir.Read access token.")
    manifest = validate_samples(directory)
    expected = {}
    for phase in manifest["phases"]:
        for entry in read_json(directory / phase["bundle"])["entry"]:
            item = entry["resource"]
            expected[f"{item['resourceType']}/{item['id']}"] = item
        if phase["name"] == phase_name:
            break
    status, metadata = get_fhir(base_url + "/metadata", token)
    if status != 200 or metadata.get("resourceType") != "CapabilityStatement" or metadata.get("fhirVersion") != "4.0.1":
        raise RuntimeError(f"FHIR metadata/authentication check failed (HTTP {status}).")
    if get_fhir(base_url + "/metadata", None)[0] != 401:
        raise RuntimeError("Unauthenticated metadata access was not rejected by APIM.")
    deadline = time.monotonic() + timeout
    while True:
        pending = 0
        for key, wanted in expected.items():
            status, actual = get_fhir(base_url + "/" + key, token)
            if status not in (200, 404):
                raise RuntimeError(f"FHIR read failed (HTTP {status}); this is not an ingestion-lag success.")
            if status != 200 or actual != wanted:
                pending += 1
        if pending == 0:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out with {pending} missing or mismatched resources; inspect ingestion failures and GoldFhirRejectedEvent.")
        time.sleep(5)
    query = urlencode({"patient": "epna-p1", "status": "in-progress"})
    status, result = get_fhir(base_url + "/Encounter?" + query, token)
    expected_ids = [] if phase_name in ("discharge", "transfer") else ["epna-e1"]
    found_ids = [entry["resource"]["id"] for entry in result.get("entry", [])]
    if status != 200 or result.get("type") != "searchset" or found_ids != expected_ids:
        raise RuntimeError("Latest-version status search returned stale or incorrect encounter state.")
    seen = []
    next_url = base_url + "/Patient?_count=1"
    for _ in range(10):
        status, page = get_fhir(next_url, token)
        if status != 200 or page.get("type") != "searchset":
            raise RuntimeError("FHIR paging did not return a searchset Bundle.")
        seen.extend(entry["resource"]["id"] for entry in page.get("entry", []))
        next_url = next((link["url"] for link in page.get("link", []) if link["relation"] == "next"), None)
        if next_url is None:
            break
        if not next_url.startswith(base_url + "/"):
            raise RuntimeError("FHIR continuation link escaped the governed APIM base URL.")
    if next_url is not None or sorted(seen) != ["epna-p1", "epna-p2", "epna-p3"]:
        raise RuntimeError("Paging failed, duplicated resources, or found non-demo patients; use an isolated POC database.")
    for path, expected_status in [("/Patient/epna-missing", 404), ("/Patient?_include=Patient:general-practitioner", 400)]:
        status, outcome = get_fhir(base_url + path, token)
        if status != expected_status or outcome.get("resourceType") != "OperationOutcome":
            raise RuntimeError("FHIR error handling did not return the expected OperationOutcome.")
    print(f"PASS: {phase_name}; {len(expected)} exact resource reads, latest-state search, paging, metadata and auth/error boundaries.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate_parser = commands.add_parser("generate")
    generate_parser.add_argument("--output-dir", type=Path, default=SAMPLES)
    generate_parser.add_argument("--start-time", default="2026-10-01T12:00:00Z", help="UTC instant, or 'now' for a current five-minute demo.")
    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--samples-dir", type=Path, default=SAMPLES)
    publish_parser = commands.add_parser("publish")
    publish_parser.add_argument("--samples-dir", type=Path, default=SAMPLES)
    publish_parser.add_argument("--phase", choices=PHASES, required=True)
    publish_parser.add_argument("--dry-run", action="store_true")
    publish_parser.add_argument("--confirm-synthetic", action="store_true")
    smoke_parser = commands.add_parser("smoke")
    smoke_parser.add_argument("--samples-dir", type=Path, default=SAMPLES)
    smoke_parser.add_argument("--phase", choices=PHASES, required=True)
    smoke_parser.add_argument("--base-url", required=True)
    smoke_parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    try:
        if args.command == "generate":
            start = (datetime.now(timezone.utc).replace(microsecond=0) - timedelta(minutes=4)
                     if args.start_time == "now" else datetime.fromisoformat(args.start_time.replace("Z", "+00:00")))
            if start.tzinfo is None:
                raise ValueError("--start-time must include a timezone.")
            generate(args.output_dir, start)
        elif args.command == "validate":
            manifest = validate_samples(args.samples_dir)
            print(f"Validated all {len(manifest['phases'])} synthetic phases, references and event envelopes.")
        elif args.command == "publish":
            publish(args.samples_dir, args.phase, args.dry_run, args.confirm_synthetic)
        else:
            if args.timeout < 0 or args.timeout > 1800:
                raise ValueError("--timeout must be between 0 and 1800 seconds.")
            smoke(args.samples_dir, args.phase, args.base_url.rstrip("/"), args.timeout)
    except (ValueError, KeyError, OSError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
