package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "HIE_TEST_MDM_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+/fhir")
class HapiMdmIntegrationTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String base = System.getenv("HIE_TEST_MDM_URL");

    @Test
    void exactEnterpriseIdsLinkToSameGoldenPatient() throws Exception {
        String value = "synthetic-" + UUID.randomUUID();
        String first = createPatient(value);
        String second = createPatient(value);
        String differentCase = createPatient(value.toUpperCase(java.util.Locale.ROOT));
        String firstGolden = awaitMatch(first);
        assertEquals(firstGolden, awaitMatch(second));
        assertNotEquals(firstGolden, awaitMatch(differentCase));
    }

    private String createPatient(String value) throws Exception {
        String body = """
                {"resourceType":"Patient","identifier":[
                 {"system":"https://example.invalid/test-eid","value":"%s"}]}
                """.formatted(value);
        var request = HttpRequest.newBuilder(URI.create(base + "/Patient"))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());
        String location = response.headers().firstValue("location").orElseThrow();
        return location.substring(location.indexOf("/Patient/") + 1, location.indexOf("/_history/"));
    }

    private String awaitMatch(String source) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/$mdm-query-links?resourceId="
                + source.replace("/", "%2F") + "&_count=100"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/fhir+json").GET().build();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            JsonNode parameters = json.readTree(response.body()).path("parameter");
            for (JsonNode parameter : parameters) {
                if (!"link".equals(parameter.path("name").asText())) {
                    continue;
                }
                String golden = null;
                String match = null;
                for (JsonNode part : parameter.path("part")) {
                    if ("goldenResourceId".equals(part.path("name").asText())) {
                        golden = part.path("valueString").asText();
                    } else if ("matchResult".equals(part.path("name").asText())) {
                        match = part.path("valueString").asText();
                    }
                }
                assertEquals("MATCH", match);
                assertTrue(golden != null && golden.startsWith("Patient/"));
                return golden;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("MDM link not created before timeout");
    }
}
