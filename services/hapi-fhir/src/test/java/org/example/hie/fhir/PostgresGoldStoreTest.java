package org.example.hie.fhir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.postgresql.ds.PGSimpleDataSource;

class PostgresGoldStoreTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "GOLD_STORE", matches = "postgresql")
    void selectsPostgresBackendFromRuntimeConfiguration() throws Exception {
        assertInstanceOf(PostgresGoldStore.class, FhirServer.configuredStore());
    }

    @Test
    void bindsTokensAndBuildsFiltersAgainstCurrentResources() {
        String hostile = "system|value'); DROP TABLE hie_gold.fhir_current; --";
        var search = SearchParameters.parse("Observation", Map.of(
                "patient", new String[] {"patient-1"}, "code", new String[] {hostile}));
        var query = PostgresGoldStore.query("Observation", search);
        assertFalse(query.sql().contains(hostile));
        assertTrue(query.sql().contains("hie_gold.fhir_current"));
        assertTrue(query.sql().contains("jsonb_array_elements"));
        assertTrue(query.parameters().contains("value'); DROP TABLE hie_gold.fhir_current; --"));
        assertTrue(query.parameters().contains("Patient/patient-1"));
    }

    @Test
    void queryFailureIsReportedAsUnavailable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("sensitive"));
        var store = new PostgresGoldStore(dataSource);
        assertThrows(GoldStore.Unavailable.class,
                () -> store.search("Patient", SearchParameters.parse("Patient", Map.of())));
        assertThrows(GoldStore.Unavailable.class, store::checkReady);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HIE_TEST_POSTGRES_URL", matches = ".+")
    void searchesPostgresWithExactTokensAndPagination() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("HIE_TEST_POSTGRES_URL"));
        source.setUser(System.getenv("HIE_TEST_POSTGRES_USER"));
        source.setPassword(System.getenv("HIE_TEST_POSTGRES_PASSWORD"));
        String id = "p-" + UUID.randomUUID();
        String json = "{\"resourceType\":\"Patient\",\"id\":\"" + id
                + "\",\"identifier\":[{\"system\":\"other\",\"value\":\"one\"},"
                + "{\"system\":\"test-system\",\"value\":\"test-value\"}]}";
        try {
            try (Connection connection = source.getConnection();
                 var insert = connection.prepareStatement("""
                         INSERT INTO hie_gold.fhir_current (resource_type, fhir_id, version_time, resource_json)
                         VALUES ('Patient', ?, now(), ?::jsonb)
                         """)) {
                insert.setString(1, id);
                insert.setString(2, json);
                insert.executeUpdate();
            }
            var store = new PostgresGoldStore(source);
            store.checkReady();
            var search = SearchParameters.parse("Patient", Map.of(
                    "_id", new String[] {id}, "identifier", new String[] {"test-system|test-value"},
                    "_count", new String[] {"1"}));
            List<String> results = store.search("Patient", search);
            assertEquals(1, results.size());
            assertTrue(results.getFirst().contains(id));
            assertTrue(store.search("Patient", SearchParameters.parse("Patient", Map.of(
                    "_id", new String[] {id}, "identifier", new String[] {"other|test-value"}))).isEmpty());
            assertTrue(store.search("Patient", SearchParameters.parse("Patient", Map.of(
                    "_id", new String[] {id}, "_offset", new String[] {"1"}))).isEmpty());
            Server server = FhirServer.create(0, "https://api.example.org/fhir", store);
            try {
                server.start();
                int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/fhir/Patient/" + id)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains(id));
            } finally {
                server.stop();
            }
        } finally {
            try (Connection connection = source.getConnection();
                 var delete = connection.prepareStatement("DELETE FROM hie_gold.fhir_current WHERE resource_type = 'Patient' AND fhir_id = ?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HIE_TEST_POSTGRES_URL", matches = ".+")
    void searchesClinicalReferencesAndAllCodings() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("HIE_TEST_POSTGRES_URL"));
        source.setUser(System.getenv("HIE_TEST_POSTGRES_USER"));
        source.setPassword(System.getenv("HIE_TEST_POSTGRES_PASSWORD"));
        String id = "o-" + UUID.randomUUID();
        String patient = "p-" + UUID.randomUUID();
        String encounter = "e-" + UUID.randomUUID();
        String json = "{\"resourceType\":\"Observation\",\"id\":\"" + id
                + "\",\"status\":\"final\",\"subject\":{\"reference\":\"Patient/" + patient
                + "\"},\"encounter\":{\"reference\":\"Encounter/" + encounter
                + "\"},\"code\":{\"coding\":[{\"system\":\"other\",\"code\":\"one\"},"
                + "{\"system\":\"test-system\",\"code\":\"test-code\"}]}}";
        try {
            try (Connection connection = source.getConnection();
                 var insert = connection.prepareStatement("""
                         INSERT INTO hie_gold.fhir_current (resource_type, fhir_id, version_time, resource_json)
                         VALUES ('Observation', ?, now(), ?::jsonb)
                         """)) {
                insert.setString(1, id);
                insert.setString(2, json);
                insert.executeUpdate();
            }
            var store = new PostgresGoldStore(source);
            var search = SearchParameters.parse("Observation", Map.of(
                    "_id", new String[] {id}, "patient", new String[] {patient},
                    "encounter", new String[] {encounter}, "status", new String[] {"final"},
                    "code", new String[] {"test-system|test-code"}));
            assertEquals(1, store.search("Observation", search).size());
            assertTrue(store.search("Observation", SearchParameters.parse("Observation", Map.of(
                    "_id", new String[] {id}, "code", new String[] {"other|test-code"}))).isEmpty());
        } finally {
            try (Connection connection = source.getConnection();
                 var delete = connection.prepareStatement("DELETE FROM hie_gold.fhir_current WHERE resource_type = 'Observation' AND fhir_id = ?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
        }
    }
}
