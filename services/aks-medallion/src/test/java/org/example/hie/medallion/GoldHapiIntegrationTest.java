package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.example.hie.medallion.StageRecords.GoldRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

@EnabledIfEnvironmentVariable(named = "HIE_TEST_HAPI_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+/fhir")
@EnabledIfEnvironmentVariable(named = "HIE_TEST_POSTGRES_URL", matches = ".+")
class GoldHapiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void currentStateAndReplayAreSynchronizedToRealJpaServer() throws Exception {
        var db = new PGSimpleDataSource();
        db.setURL(System.getenv("HIE_TEST_POSTGRES_URL"));
        db.setUser(System.getenv("HIE_TEST_POSTGRES_USER"));
        db.setPassword(System.getenv("HIE_TEST_POSTGRES_PASSWORD"));
        String base = System.getenv("HIE_TEST_HAPI_URL");
        HttpClient http = HttpClient.newHttpClient();
        var client = new HapiVersionedClient(http, JSON, base, "test-token");
        var store = new PostgresGoldStore(db);
        String id = "test-" + UUID.randomUUID();
        String source = "test-" + UUID.randomUUID();

        store.commit(record("first", source, id, "2026-01-02T00:00:00Z", true));
        store.synchronizeCurrent("Patient", id, client);
        JsonNode first = read(http, base, id);
        String firstVersion = first.path("meta").path("versionId").asText();
        assertEquals(true, first.path("active").asBoolean());

        store.commit(record("older", source, id, "2026-01-01T00:00:00Z", false));
        store.synchronizeCurrent("Patient", id, client);
        assertEquals(firstVersion, read(http, base, id).path("meta").path("versionId").asText());
        store.commit(record("first", source, id, "2026-01-02T00:00:00Z", true));
        store.synchronizeCurrent("Patient", id, client);
        assertEquals(firstVersion, read(http, base, id).path("meta").path("versionId").asText());

        assertThrows(PermanentStageException.class, () ->
                store.commit(record("conflict", source, id, "2026-01-02T00:00:00Z", false)));
        store.commit(record("newer", source, id, "2026-01-03T00:00:00Z", false));
        store.synchronizeCurrent("Patient", id, client);
        JsonNode newer = read(http, base, id);
        assertEquals(false, newer.path("active").asBoolean());
        assertNotEquals(firstVersion, newer.path("meta").path("versionId").asText());
    }

    @Test
    void concurrentUpdatesConvergeOnNewestSourceVersion() throws Exception {
        var db = new PGSimpleDataSource();
        db.setURL(System.getenv("HIE_TEST_POSTGRES_URL"));
        db.setUser(System.getenv("HIE_TEST_POSTGRES_USER"));
        db.setPassword(System.getenv("HIE_TEST_POSTGRES_PASSWORD"));
        String base = System.getenv("HIE_TEST_HAPI_URL");
        HttpClient http = HttpClient.newHttpClient();
        var client = new HapiVersionedClient(http, JSON, base, "test-token");
        var store = new PostgresGoldStore(db);
        String id = "test-" + UUID.randomUUID();
        String source = "test-" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int day = 1; day <= 4; day++) {
                final int version = day;
                tasks.add(executor.submit(() -> {
                    try {
                        store.commit(record("event-" + version, source, id,
                                "2026-01-0" + version + "T00:00:00Z", version == 4));
                        store.synchronizeCurrent("Patient", id, client);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }
            for (var task : tasks) {
                task.get();
            }
        }
        JsonNode current = read(http, base, id);
        assertEquals(true, current.path("active").asBoolean());
        assertEquals("2026-01-04T00:00:00Z",
                HapiVersionedClient.extension(current, "urn:hie:source-version", "valueInstant"));
    }

    @Test
    void numericNonPatientIdCanBeCreatedAndReplayed() throws Exception {
        String base = System.getenv("HIE_TEST_HAPI_URL");
        HttpClient http = HttpClient.newHttpClient();
        var client = new HapiVersionedClient(http, JSON, base, "test-token");
        String id = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        var resource = JSON.createObjectNode().put("resourceType", "Observation")
                .put("id", id).put("status", "final");
        resource.putObject("code").put("text", "synthetic");
        Instant version = Instant.parse("2026-01-02T00:00:00Z");
        client.write(resource, version);
        var response = http.send(HttpRequest.newBuilder(URI.create(base + "/Observation/" + id))
                .header("Accept", "application/fhir+json").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode first = JSON.readTree(response.body());
        assertEquals(id, first.path("id").asText());
        client.write(resource, version);
        var replay = http.send(HttpRequest.newBuilder(URI.create(base + "/Observation/" + id))
                .header("Accept", "application/fhir+json").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(first.path("meta").path("versionId"), JSON.readTree(replay.body()).path("meta").path("versionId"));
    }

    private static GoldRecord record(String event, String source, String id, String version, boolean active)
            throws Exception {
        JsonNode payload = JSON.readTree("""
                {"resourceType":"Patient","id":"%s","meta":{"lastUpdated":"%s"},"active":%s}
                """.formatted(id, version, active));
        return new GoldRecord("1", event, source, "test", Instant.parse(version), Instant.now(),
                "test.json", "test-hash", payload);
    }

    private static JsonNode read(HttpClient http, String base, String id) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/Patient/" + id))
                .header("Accept", "application/fhir+json").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
    }
}
