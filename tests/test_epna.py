import contextlib
from datetime import datetime, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("epna", ROOT / "scripts" / "epna.py")
epna = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(epna)


class EpnaFixturesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hie-epna-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        with contextlib.redirect_stdout(io.StringIO()):
            epna.generate(self.directory, datetime(2026, 10, 1, 12, tzinfo=timezone.utc))

    def test_checked_in_fixtures_are_reproducible(self):
        for generated in self.directory.iterdir():
            self.assertEqual(generated.read_bytes(), (epna.SAMPLES / generated.name).read_bytes(), generated.name)

    def test_complete_lifecycle_and_event_shapes(self):
        manifest = epna.validate_samples(self.directory)
        current = {}
        resource_events = 0
        monitoring_events = 0
        for phase in manifest["phases"]:
            events = [json.loads(line) for line in (self.directory / phase["events"]).read_text().splitlines()]
            for event in events:
                self.assertEqual(
                    {"EventKind", "EventTime", "SourceEventId", "SourceMessageType", "Resource", "Signal"}, set(event))
                if event["EventKind"] == "resource":
                    resource_events += 1
                else:
                    monitoring_events += 1
                    signal = event["Signal"]
                    current[signal["EncounterId"]] = signal
                    self.assertIsNone(signal["AlertScore"])
            self.assertEqual(phase["expectedActiveEncounters"],
                             sorted(key for key, signal in current.items() if signal["AlertEligible"]))
        self.assertEqual(31, resource_events)
        self.assertEqual(10, monitoring_events)
        self.assertEqual("discharged", current["epna-e1"]["MonitoringStatus"])
        self.assertEqual("not-qualified", current["epna-e2"]["MonitoringStatus"])
        self.assertEqual("transferred", current["epna-e3"]["MonitoringStatus"])

    def test_status_updates_preserve_original_clinical_start_times(self):
        qualification = epna.read_json(self.directory / "02-qualification.bundle.json")
        monitoring = epna.read_json(self.directory / "03-monitoring.bundle.json")
        original_order = next(entry["resource"] for entry in qualification["entry"]
                              if entry["resource"]["resourceType"] == "ServiceRequest")
        completed_order = next(entry["resource"] for entry in monitoring["entry"]
                               if entry["resource"]["resourceType"] == "ServiceRequest")
        self.assertEqual(original_order["authoredOn"], completed_order["authoredOn"])
        self.assertNotEqual(original_order["meta"]["lastUpdated"], completed_order["meta"]["lastUpdated"])
        self.assertEqual("completed", completed_order["status"])
        admission = epna.read_json(self.directory / "01-admission.bundle.json")
        discharge = epna.read_json(self.directory / "04-discharge.bundle.json")
        start = next(entry["resource"]["period"]["start"] for entry in admission["entry"]
                     if entry["resource"]["id"] == "epna-e1")
        finished = next(entry["resource"] for entry in discharge["entry"] if entry["resource"]["id"] == "epna-e1")
        self.assertEqual(start, finished["period"]["start"])
        self.assertGreater(finished["period"]["end"], start)

    def test_non_synthetic_data_is_rejected_before_publication(self):
        path = self.directory / "01-admission.bundle.json"
        bundle = epna.read_json(path)
        bundle["entry"][0]["resource"]["meta"]["tag"] = []
        path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "synthetic data-origin"):
            epna.publish(self.directory, "admission", False, True)

    def test_dangling_references_are_rejected(self):
        path = self.directory / "01-admission.bundle.json"
        bundle = epna.read_json(path)
        bundle["entry"][1]["resource"]["subject"]["reference"] = "Patient/missing"
        path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "dangling"):
            epna.validate_samples(self.directory)

    def test_dry_run_does_not_require_secrets_or_network(self):
        with patch.dict(epna.os.environ, {}, clear=True), patch.object(epna.HTTP, "open") as network:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                epna.publish(self.directory, "qualification", True, False)
            network.assert_not_called()
            self.assertIn("10 synthetic qualification events", output.getvalue())

    def test_publication_requires_explicit_confirmation(self):
        with self.assertRaisesRegex(ValueError, "--confirm-synthetic"):
            epna.publish(self.directory, "admission", False, False)

    def test_bearer_tokens_cannot_follow_redirects(self):
        self.assertIsNone(epna.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example"))

    def test_gold_latest_state_precedes_alert_filters(self):
        schema = (ROOT / "fabric" / "kql" / "DatabaseSchema.kql").read_text()
        function = schema.split(") EpnaAlertCandidates() {", 1)[1].split("}", 1)[0]
        self.assertLess(function.index("summarize arg_max"), function.index("where AlertEligible"))
        self.assertLess(function.index("summarize arg_max"), function.index("where EventTime"))
        self.assertIn("by ResourceType, FhirId", schema.split("GoldFhirCurrent() {", 1)[1].split("}", 1)[0])

    def test_eventstream_template_uses_documented_direct_ingestion_contract(self):
        topology = epna.read_json(ROOT / "fabric" / "eventstream" / "GoldFhirEventstream.template.json")
        destination = topology["destinations"][0]["properties"]
        self.assertEqual("CustomEndpoint", topology["sources"][0]["type"])
        self.assertEqual("DirectIngestion", destination["dataIngestionMode"])
        self.assertEqual("__EVENTHOUSE_ID__", destination["itemId"])
        self.assertEqual("GoldFhirEvent", destination["tableName"])
        self.assertEqual("GoldFhirEventJson", destination["mappingRuleName"])
        self.assertEqual([], topology["operators"])

    def test_publisher_sends_individual_json_events_with_stable_ids(self):
        try:
            from azure.eventhub import TransportType
        except ImportError:
            self.skipTest("Install scripts/requirements.txt to exercise the Event Hubs SDK contract.")
        with patch("azure.eventhub.EventHubProducerClient.from_connection_string") as factory:
            producer = factory.return_value.__enter__.return_value
            batch = producer.create_batch.return_value
            with patch.dict(epna.os.environ, {"GOLD_EVENTSTREAM_CONNECTION_STRING": "mock-not-a-secret"}):
                with contextlib.redirect_stdout(io.StringIO()):
                    epna.publish(self.directory, "qualification", False, True)
            self.assertEqual(TransportType.AmqpOverWebsocket, factory.call_args.kwargs["transport_type"])
            self.assertEqual(10, batch.add.call_count)
            actual = [json.loads(call.args[0].body_as_str()) for call in batch.add.call_args_list]
            self.assertEqual(7, sum(event["EventKind"] == "resource" for event in actual))
            self.assertEqual(3, sum(event["EventKind"] == "monitoring" for event in actual))
            self.assertEqual(10, len({event["SourceEventId"] for event in actual}))
            self.assertTrue(all(call.args[0].content_type == "application/json" for call in batch.add.call_args_list))
            producer.send_batch.assert_called_once_with(batch)

    def test_publisher_failure_does_not_disclose_credentials(self):
        try:
            from azure.core.exceptions import AzureError
        except ImportError:
            self.skipTest("Install scripts/requirements.txt to exercise the Event Hubs SDK contract.")
        with patch("azure.eventhub.EventHubProducerClient.from_connection_string",
                   side_effect=AzureError("SharedAccessKey=do-not-print")):
            with patch.dict(epna.os.environ, {"GOLD_EVENTSTREAM_CONNECTION_STRING": "mock-not-a-secret"}):
                with self.assertRaises(RuntimeError) as failure:
                    epna.publish(self.directory, "admission", False, True)
            self.assertNotIn("do-not-print", str(failure.exception))
            self.assertIn("publication failed", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
