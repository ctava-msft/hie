package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.example.hie.medallion.StageRecords.GoldRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

@EnabledIfEnvironmentVariable(named = "HIE_TEST_MDM_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+/fhir")
@EnabledIfEnvironmentVariable(named = "HIE_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "HIE_TEST_HAPI_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+/fhir")
class SilverHapiMdmIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void linksPatientsAndRewritesReferencesWithoutPromotingMissingIdentifiers() throws Exception {
        var db = new PGSimpleDataSource();
        db.setURL(System.getenv("HIE_TEST_POSTGRES_URL"));
        db.setUser(System.getenv("HIE_TEST_POSTGRES_USER"));
        db.setPassword(System.getenv("HIE_TEST_POSTGRES_PASSWORD"));
        var client = new HapiVersionedClient(HttpClient.newHttpClient(), JSON,
                System.getenv("HIE_TEST_MDM_URL"), "test-token");
        var mdm = new SilverHapiMdm(client, db, "https://example.invalid/test-eid");
        String identity = "eid-" + UUID.randomUUID();
        String source = "synthetic-" + UUID.randomUUID();
        JsonNode first = patient("p1", identity);
        JsonNode second = patient("p2", identity);
        JsonNode golden1 = await(mdm, source, first);
        JsonNode golden2 = await(mdm, source, second);
        assertEquals(golden1.path("id").asText(), golden2.path("id").asText());
        assertThrows(IllegalStateException.class, () -> mdm.normalize(source, first));
        String observationId = "o-" + UUID.randomUUID();
        JsonNode observation = JSON.readTree("""
                {"resourceType":"Observation","id":"%s","status":"final","code":{"text":"synthetic"},
                 "meta":{"lastUpdated":"2026-01-01T00:00:00Z"},
                 "subject":{"reference":"Patient/p2"}}
                """.formatted(observationId));
        JsonNode normalizedObservation = mdm.normalize(source, observation);
        assertEquals("Patient/" + golden2.path("id").asText(),
                normalizedObservation.path("subject").path("reference").asText());
        assertThrows(PermanentStageException.class, () -> mdm.normalize(source, JSON.readTree("""
                {"resourceType":"Patient","id":"p3","meta":{"lastUpdated":"2026-01-01T00:00:00Z"}}
                """)));

        String goldBase = System.getenv("HIE_TEST_HAPI_URL");
        HttpClient http = HttpClient.newHttpClient();
        var goldClient = new HapiVersionedClient(http, JSON, goldBase, "test-token");
        var store = new PostgresGoldStore(db);
        commit(store, goldClient, source, "patient", golden2);
        commit(store, goldClient, source, "observation", normalizedObservation);
        var response = http.send(HttpRequest.newBuilder(URI.create(goldBase + "/Observation/" + observationId))
                .header("Accept", "application/fhir+json").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("Patient/" + golden2.path("id").asText(),
                JSON.readTree(response.body()).path("subject").path("reference").asText());
    }

    private static void commit(PostgresGoldStore store, HapiVersionedClient client, String source,
                               String eventId, JsonNode resource) {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        store.commit(new GoldRecord("1", eventId, source, "test", time, time, "test.json", "hash", resource));
        store.synchronizeCurrent(resource.path("resourceType").asText(), resource.path("id").asText(), client);
    }

    private static JsonNode patient(String id, String eid) throws Exception {
        return JSON.readTree("""
                {"resourceType":"Patient","id":"%s","meta":{"lastUpdated":"2026-01-01T00:00:00Z"},
                 "identifier":[{"system":"https://example.invalid/test-eid","value":"%s"}]}
                """.formatted(id, eid));
    }

    private static JsonNode await(SilverHapiMdm mdm, String source, JsonNode resource) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        IllegalStateException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return mdm.normalize(source, resource);
            } catch (IllegalStateException exception) {
                last = exception;
                Thread.sleep(500);
            }
        }
        throw new AssertionError("MDM did not finish processing", last);
    }
}
