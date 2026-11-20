import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

SERVICE = Path(__file__).resolve().parents[1] / "services" / "fabric-rt-actuator"


class PowerAutomateContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.connector = json.loads((SERVICE / "power-automate" / "connector.swagger.json").read_text())
        cls.policy = ET.parse(SERVICE / "infra" / "power-automate-policy.xml").getroot()

    def test_connector_exposes_only_https_action_intake(self):
        self.assertEqual("2.0", self.connector["swagger"])
        self.assertEqual(["https"], self.connector["schemes"])
        self.assertEqual("/activator", self.connector["basePath"])
        self.assertEqual({"/actions"}, set(self.connector["paths"]))
        self.assertEqual({"post"}, set(self.connector["paths"]["/actions"]))
        operation = self.connector["paths"]["/actions"]["post"]
        self.assertEqual("QueueActivatorAction", operation["operationId"])
        self.assertEqual(
            "#/definitions/ActivatorAction", operation["parameters"][0]["schema"]["$ref"])
        self.assertEqual("#/definitions/QueueReceipt", operation["responses"]["202"]["schema"]["$ref"])

    def test_subscription_credential_is_in_a_connection_header(self):
        self.assertEqual([{"subscriptionKey": []}], self.connector["security"])
        self.assertEqual(
            {"type": "apiKey", "in": "header", "name": "Ocp-Apim-Subscription-Key"},
            self.connector["securityDefinitions"]["subscriptionKey"])

    def test_envelope_has_only_two_text_inputs_and_a_safe_key(self):
        schema = self.connector["definitions"]["ActivatorAction"]
        self.assertEqual({"eventId", "payloadJson"}, set(schema["required"]))
        self.assertEqual(set(schema["required"]), set(schema["properties"]))
        self.assertFalse(schema["additionalProperties"])
        event_id = schema["properties"]["eventId"]
        self.assertEqual((1, 128), (event_id["minLength"], event_id["maxLength"]))
        self.assertIsNotNone(re.fullmatch(event_id["pattern"], "event-001.rule:2"))
        self.assertIsNotNone(re.fullmatch(event_id["pattern"], "x" * 128))
        for invalid in ("", "x" * 129, "event with spaces", "event/id", "event\u00e9"):
            self.assertIsNone(re.fullmatch(event_id["pattern"], invalid))
        self.assertEqual("string", schema["properties"]["payloadJson"]["type"])

    def test_gateway_preserves_body_and_only_rewrites_the_intake_path(self):
        rewrite = self.policy.find("inbound/rewrite-uri")
        self.assertEqual("/api/webhooks/power-automate", rewrite.attrib["template"])
        self.assertEqual("false", rewrite.attrib["copy-unmatched-params"])
        self.assertIsNone(self.policy.find(".//set-body"))
        validation = self.policy.find("inbound/validate-content")
        self.assertEqual("65536", validation.attrib["max-size"])
        self.assertEqual("prevent", validation.attrib["size-exceeded-action"])
        self.assertEqual("prevent", validation.find("content").attrib["action"])

    def test_gateway_injects_webhook_key_without_forwarding_its_subscription_key(self):
        headers = {header.attrib["name"]: header for header in self.policy.findall("inbound/set-header")}
        self.assertEqual("override", headers["X-API-Key"].attrib["exists-action"])
        self.assertEqual("{{fabric-activator-webhook-key}}", headers["X-API-Key"].find("value").text)
        self.assertEqual("delete", headers["Ocp-Apim-Subscription-Key"].attrib["exists-action"])
        self.assertEqual("delete", headers["Authorization"].attrib["exists-action"])

    def test_gateway_does_not_follow_redirects_or_mask_errors(self):
        forward = self.policy.find("backend/forward-request")
        self.assertEqual("false", forward.attrib["follow-redirects"])
        self.assertEqual("30", forward.attrib["timeout"])
        self.assertIsNone(self.policy.find(".//return-response"))
        self.assertIsNone(self.policy.find(".//set-status"))


if __name__ == "__main__":
    unittest.main()
