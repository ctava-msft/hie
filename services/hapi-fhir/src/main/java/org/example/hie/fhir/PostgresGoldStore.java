package org.example.hie.fhir;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PostgresGoldStore implements GoldStore {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresGoldStore.class);
    private final DataSource dataSource;

    public PostgresGoldStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    record Query(String sql, List<Object> parameters) { }

    static Query query(String resourceType, SearchParameters search) {
        if (!SearchParameters.SUPPORTED.containsKey(resourceType)) {
            throw new IllegalArgumentException("Unsupported resource type.");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT resource_json::text FROM hie_gold.fhir_current WHERE resource_type = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(resourceType);
        for (String key : List.of("_id", "patient", "encounter", "status")) {
            if (!search.values().containsKey(key)) {
                continue;
            }
            String column = switch (key) {
                case "_id" -> "fhir_id";
                case "patient" -> "COALESCE(resource_json #>> '{subject,reference}', resource_json #>> '{patient,reference}')";
                case "encounter" -> "resource_json #>> '{encounter,reference}'";
                case "status" -> "resource_json ->> 'status'";
                default -> throw new IllegalArgumentException("Unsupported field.");
            };
            sql.append(" AND ").append(column).append(" = ?");
            parameters.add(search.values().get(key));
        }
        for (String key : List.of("identifier", "code", "clinical-status")) {
            if (!search.values().containsKey(key)) {
                continue;
            }
            SearchParameters.Token token = SearchParameters.Token.parse(search.values().get(key));
            String array = switch (key) {
                case "identifier" -> "resource_json -> 'identifier'";
                case "code" -> "resource_json #> '{code,coding}'";
                case "clinical-status" -> "resource_json #> '{clinicalStatus,coding}'";
                default -> throw new IllegalArgumentException("Unsupported token field.");
            };
            sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(")
                    .append(array).append(", '[]'::jsonb)) AS coding WHERE ");
            if (token.hasSystem()) {
                sql.append("COALESCE(coding ->> 'system', '') = ? AND ");
                parameters.add(token.system());
            }
            sql.append("coding ->> '").append(key.equals("identifier") ? "value" : "code").append("' = ?)");
            parameters.add(token.value());
        }
        sql.append(" ORDER BY fhir_id LIMIT ? OFFSET ?");
        parameters.add(search.count() + 1);
        parameters.add(search.offset());
        return new Query(sql.toString(), List.copyOf(parameters));
    }

    @Override
    public List<String> search(String resourceType, SearchParameters search) {
        Query query = query(resourceType, search);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(20);
            for (int i = 0; i < query.parameters().size(); i++) {
                statement.setObject(i + 1, query.parameters().get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<String> resources = new ArrayList<>();
                while (result.next()) {
                    String json = result.getString(1);
                    if (json == null || json.isBlank()) {
                        throw new GoldStore.Unavailable();
                    }
                    resources.add(json);
                }
                return resources;
            }
        } catch (SQLException exception) {
            LOG.error("PostgreSQL Gold query failed: {}", exception.getClass().getSimpleName());
            throw new GoldStore.Unavailable();
        }
    }

    @Override
    public void checkReady() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT resource_json FROM hie_gold.fhir_current LIMIT 0")) {
            statement.setQueryTimeout(20);
            try (ResultSet ignored = statement.executeQuery()) {
                // Readiness verifies that the application can query the projection.
            }
        } catch (SQLException exception) {
            LOG.error("PostgreSQL Gold readiness failed: {}", exception.getClass().getSimpleName());
            throw new GoldStore.Unavailable();
        }
    }
}
