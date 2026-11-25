package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.example.hie.medallion.StageRecords.GoldRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class PostgresGoldStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @Import(PostgresGoldStore.class)
    static class StoreConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @Import({PostgresGoldStore.class, GoldHapiClient.class, SilverHapiMdm.class})
    static class JpaConfiguration { }

    @Test
    void adxBackendDoesNotCreatePostgresStore() {
        new ApplicationContextRunner().withUserConfiguration(StoreConfiguration.class)
                .withPropertyValues("app.role=gold", "app.gold-backend=adx")
                .run(context -> assertNull(context.getBeanProvider(PostgresGoldStore.class).getIfAvailable()));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POSTGRES_HOST", matches = ".+")
    void postgresBackendCreatesStoreInSpring() {
        new ApplicationContextRunner().withUserConfiguration(StoreConfiguration.class)
                .withPropertyValues("app.role=gold", "app.gold-backend=postgresql")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(PostgresGoldStore.class));
                });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POSTGRES_HOST", matches = ".+")
    void jpaClientsAndStoreAreCreatedForTheirRoles() {
        var runner = new ApplicationContextRunner().withUserConfiguration(JpaConfiguration.class)
                .withBean(HttpClient.class, HttpClient::newHttpClient)
                .withBean(ObjectMapper.class, ObjectMapper::new);
        runner.withPropertyValues("app.role=gold", "app.gold-backend=postgresql-hapi",
                        "app.hapi-gold-url=https://example.invalid/fhir", "app.hapi-gold-token=test-token")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(PostgresGoldStore.class));
                    assertNotNull(context.getBean(GoldHapiClient.class));
                });
        runner.withPropertyValues("app.role=silver", "app.gold-backend=adx", "app.silver-hapi-enabled=true",
                        "app.hapi-silver-url=https://example.invalid/fhir", "app.hapi-silver-token=test-token",
                        "app.patient-identifier-system=https://example.invalid/eid")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(SilverHapiMdm.class));
                });
    }

    @Test
    void missingResourceVersionIsRejectedBeforeConnecting() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        var store = new PostgresGoldStore(dataSource);
        var error = assertThrows(PermanentStageException.class, () -> store.commit(record(
                "event", "source", "{\"resourceType\":\"Patient\",\"id\":\"p1\"}")));
        assertEquals("MissingFhirVersionTime", error.reason());
        verifyNoInteractions(dataSource);
    }

    @Test
    void subMicrosecondVersionIsRejectedRatherThanRounded() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        var error = assertThrows(PermanentStageException.class, () -> new PostgresGoldStore(dataSource)
                .commit(record("event", "source", patient("p1", "2026-01-01T00:00:00.000000001Z", true))));
        assertEquals("UnsupportedFhirVersionPrecision", error.reason());
        verifyNoInteractions(dataSource);
    }

    @Test
    void databaseFailureIsNotReportedAsSuccess() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("sensitive"));
        var store = new PostgresGoldStore(dataSource);
        assertThrows(IllegalStateException.class, () -> store.commit(record("event", "source",
                patient("p1", "2026-01-01T00:00:00Z", true))));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HIE_TEST_POSTGRES_URL", matches = ".+")
    void transactionsDeduplicateRejectConflictsAndIgnoreOlderVersions() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("HIE_TEST_POSTGRES_URL"));
        source.setUser(System.getenv("HIE_TEST_POSTGRES_USER"));
        source.setPassword(System.getenv("HIE_TEST_POSTGRES_PASSWORD"));
        String identity = "test-" + UUID.randomUUID();
        String id = "p-" + UUID.randomUUID();
        var store = new PostgresGoldStore(source);
        String first = patient(id, "2026-01-02T00:00:00Z", true);
        try {
            store.commit(record("one", identity, first));
            store.commit(record("one", identity, first));
            store.commit(record("older", identity, patient(id, "2026-01-01T00:00:00Z", false)));
            assertEquals(2, countEvents(source, identity));
            assertTrue(currentActive(source, id));

            var duplicateConflict = assertThrows(PermanentStageException.class,
                    () -> store.commit(record("one", identity,
                            patient(id, "2026-01-02T00:00:00Z", false))));
            assertEquals("ConflictingEventIdentity", duplicateConflict.reason());
            var versionConflict = assertThrows(PermanentStageException.class,
                    () -> store.commit(record("conflict", identity,
                            patient(id, "2026-01-02T00:00:00Z", false))));
            assertEquals("ConflictingFhirVersion", versionConflict.reason());
            assertEquals(2, countEvents(source, identity));

            store.commit(record("newer", identity, patient(id, "2026-01-03T00:00:00Z", false)));
            assertFalse(currentActive(source, id));
            assertEquals(3, countEvents(source, identity));
        } finally {
            try (Connection connection = source.getConnection();
                 var events = connection.prepareStatement("DELETE FROM hie_gold.fhir_event WHERE source = ?");
                 var current = connection.prepareStatement("DELETE FROM hie_gold.fhir_current WHERE resource_type = 'Patient' AND fhir_id = ?")) {
                events.setString(1, identity);
                events.executeUpdate();
                current.setString(1, id);
                current.executeUpdate();
            }
        }
    }

    private static GoldRecord record(String eventId, String source, String payload) throws Exception {
        return new GoldRecord("1.0", eventId, source, "ADT", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.now(), "silver.json", "hash", JSON.readTree(payload));
    }

    private static String patient(String id, String time, boolean active) {
        return "{\"resourceType\":\"Patient\",\"id\":\"" + id + "\",\"meta\":{\"lastUpdated\":\""
                + time + "\"},\"active\":" + active + "}";
    }

    private static int countEvents(DataSource source, String identity) throws SQLException {
        try (Connection connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM hie_gold.fhir_event WHERE source = ?")) {
            statement.setString(1, identity);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static boolean currentActive(DataSource source, String id) throws SQLException {
        try (Connection connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT (resource_json->>'active')::boolean FROM hie_gold.fhir_current WHERE resource_type = 'Patient' AND fhir_id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }
}
