package org.example.hie.medallion;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import javax.sql.DataSource;
import org.example.hie.medallion.StageRecords.GoldRecord;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${app.role}' == 'gold' and '${app.gold-backend:adx}' == 'postgresql'")
class PostgresGoldStore {
    private static final Set<String> TYPES = Set.of("Patient", "Encounter", "Condition",
            "Observation", "ServiceRequest", "DiagnosticReport", "Provenance");
    private final DataSource dataSource;

    PostgresGoldStore() {
        this(configuredDataSource());
    }

    PostgresGoldStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static DataSource configuredDataSource() {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {required("POSTGRES_HOST")});
        source.setDatabaseName(required("POSTGRES_DATABASE"));
        source.setUser(required("POSTGRES_USER"));
        source.setPassword(required("POSTGRES_PASSWORD"));
        source.setSslMode("verify-full");
        String rootCert = System.getenv("POSTGRES_SSL_ROOT_CERT");
        if (rootCert != null && !rootCert.isBlank()) {
            source.setSslRootCert(rootCert);
        }
        source.setConnectTimeout(10);
        source.setSocketTimeout(30);
        return source;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + name);
        }
        return value;
    }

    void commit(GoldRecord gold) {
        JsonNode payload = gold.payload();
        if (payload == null || !payload.isObject()) {
            throw new PermanentStageException("InvalidFhirIdentity", null);
        }
        String type = payload.path("resourceType").asText("");
        String id = payload.path("id").asText("");
        if (!payload.path("resourceType").isTextual() || !payload.path("id").isTextual()
                || !TYPES.contains(type) || !id.matches("[A-Za-z0-9.-]{1,64}")
                || gold.source() == null || gold.source().isBlank()
                || gold.eventId() == null || gold.eventId().isBlank() || gold.eventTime() == null) {
            throw new PermanentStageException("InvalidFhirIdentity", null);
        }
        Instant version;
        try {
            version = OffsetDateTime.parse(payload.path("meta").path("lastUpdated").asText()).toInstant();
        } catch (DateTimeParseException exception) {
            throw new PermanentStageException("MissingFhirVersionTime", exception);
        }
        if (!version.equals(version.truncatedTo(ChronoUnit.MICROS))) {
            throw new PermanentStageException("UnsupportedFhirVersionPrecision", null);
        }
        String json = payload.toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (insertEvent(connection, gold, type, id, version, json)) {
                    updateCurrent(connection, type, id, version, json);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("PostgreSQL Gold transaction failed", exception);
        }
    }

    private boolean insertEvent(Connection connection, GoldRecord gold, String type, String id,
                                Instant version, String json) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO hie_gold.fhir_event
                  (source, event_id, resource_type, fhir_id, version_time, event_time, silver_blob, resource_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (source, event_id) DO NOTHING
                """)) {
            statement.setString(1, gold.source());
            statement.setString(2, gold.eventId());
            statement.setString(3, type);
            statement.setString(4, id);
            statement.setTimestamp(5, Timestamp.from(version));
            statement.setTimestamp(6, Timestamp.from(gold.eventTime()));
            statement.setString(7, gold.silverBlob());
            statement.setString(8, json);
            if (statement.executeUpdate() == 1) {
                return true;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource_type = ? AND fhir_id = ? AND version_time = ? AND resource_json = ?::jsonb
                FROM hie_gold.fhir_event WHERE source = ? AND event_id = ?
                """)) {
            statement.setString(1, type);
            statement.setString(2, id);
            statement.setTimestamp(3, Timestamp.from(version));
            statement.setString(4, json);
            statement.setString(5, gold.source());
            statement.setString(6, gold.eventId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new PermanentStageException("ConflictingEventIdentity", null);
                }
            }
        }
        return false;
    }

    private void updateCurrent(Connection connection, String type, String id, Instant version, String json)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO hie_gold.fhir_current (resource_type, fhir_id, version_time, resource_json)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (resource_type, fhir_id) DO UPDATE
                SET version_time = EXCLUDED.version_time, resource_json = EXCLUDED.resource_json
                WHERE hie_gold.fhir_current.version_time < EXCLUDED.version_time
                """)) {
            statement.setString(1, type);
            statement.setString(2, id);
            statement.setTimestamp(3, Timestamp.from(version));
            statement.setString(4, json);
            if (statement.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT version_time, resource_json = ?::jsonb
                FROM hie_gold.fhir_current WHERE resource_type = ? AND fhir_id = ?
                """)) {
            statement.setString(1, json);
            statement.setString(2, type);
            statement.setString(3, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Current resource was not found");
                }
                if (result.getTimestamp(1).toInstant().equals(version) && !result.getBoolean(2)) {
                    throw new PermanentStageException("ConflictingFhirVersion", null);
                }
            }
        }
    }
}
