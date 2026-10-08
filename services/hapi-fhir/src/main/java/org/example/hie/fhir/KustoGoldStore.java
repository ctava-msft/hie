package org.example.hie.fhir;

import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.ClientRequestProperties;
import com.microsoft.azure.kusto.data.KustoResultSetTable;
import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import com.microsoft.azure.kusto.data.exceptions.KustoServiceQueryError;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KustoGoldStore implements GoldStore {
    private static final Logger LOG = LoggerFactory.getLogger(KustoGoldStore.class);
    private final Client client;
    private final String database;

    public KustoGoldStore(Client client, String database) {
        this.client = client;
        this.database = database;
    }

    public record Query(String text, ClientRequestProperties properties) { }

    static Query query(String resourceType, SearchParameters search) {
        if (!SearchParameters.SUPPORTED.containsKey(resourceType)) {
            throw new IllegalArgumentException("Unsupported resource type.");
        }
        ClientRequestProperties properties = properties();
        properties.setParameter("p_type", resourceType);
        properties.setParameter("p_offset", search.offset());
        properties.setParameter("p_limit", search.count() + 1);
        StringBuilder declarations = new StringBuilder(
                "declare query_parameters(p_type:string, p_offset:long, p_limit:long");
        StringBuilder body = new StringBuilder("""
                GoldFhirCurrent()
                | where ResourceType == p_type
                """);
        for (String key : List.of("_id", "patient", "encounter", "status")) {
            if (!search.values().containsKey(key)) {
                continue;
            }
            String parameter = "p_" + key.replace("_", "");
            String column = switch (key) {
                case "_id" -> "FhirId";
                case "patient" -> "PatientReference";
                case "encounter" -> "EncounterReference";
                case "status" -> "tostring(ResourceJson.status)";
                default -> throw new IllegalArgumentException("Unsupported field.");
            };
            declarations.append(", ").append(parameter).append(":string");
            properties.setParameter(parameter, search.values().get(key));
            body.append("| where ").append(column).append(" == ").append(parameter).append("\n");
        }
        for (String key : List.of("identifier", "code", "clinical-status")) {
            if (!search.values().containsKey(key)) {
                continue;
            }
            SearchParameters.Token token = SearchParameters.Token.parse(search.values().get(key));
            String parameter = "p_" + key.replace("-", "_");
            String array = switch (key) {
                case "identifier" -> "ResourceJson.identifier";
                case "code" -> "ResourceJson.code.coding";
                case "clinical-status" -> "ResourceJson.clinicalStatus.coding";
                default -> throw new IllegalArgumentException("Unsupported token field.");
            };
            declarations.append(", ").append(parameter).append("_system:string, ")
                    .append(parameter).append("_value:string, ").append(parameter).append("_has_system:bool");
            properties.setParameter(parameter + "_system", token.system());
            properties.setParameter(parameter + "_value", token.value());
            properties.setParameter(parameter + "_has_system", token.hasSystem());
            body.append("| mv-apply SearchCoding = ").append(array).append(" on (\n")
                    .append("  where (not(").append(parameter).append("_has_system) or tostring(SearchCoding.system) == ")
                    .append(parameter).append("_system)\n")
                    .append("  and tostring(SearchCoding.").append(key.equals("identifier") ? "value" : "code")
                    .append(") == ").append(parameter).append("_value\n")
                    .append("  | take 1)\n");
        }
        body.append("""
                | order by FhirId asc
                | serialize RowNumber = row_number()
                | where RowNumber > p_offset
                | take p_limit
                | project ResourceJson = tostring(ResourceJson)
                """);
        return new Query(declarations.append(");\n").append(body).toString(), properties);
    }

    private static ClientRequestProperties properties() {
        ClientRequestProperties properties = new ClientRequestProperties();
        properties.setClientRequestId("HapiGold.Query;" + UUID.randomUUID());
        properties.setApplication("hie-hapi-fhir");
        properties.setTimeoutInMilliSec(20_000L);
        properties.setOption("query_results_cache_max_age", "00:00:00");
        properties.setOption("truncationmaxrecords", SearchParameters.MAX_COUNT + 1);
        properties.setOption("truncationmaxsize", 4 * 1024 * 1024);
        properties.setOption("deferpartialqueryfailures", false);
        return properties;
    }

    @Override
    public List<String> search(String resourceType, SearchParameters search) {
        Query query = query(resourceType, search);
        return execute(query);
    }

    @Override
    public void checkReady() {
        execute(new Query("GoldFhirCurrent() | project ResourceJson = tostring(ResourceJson) | take 0", properties()));
    }

    private List<String> execute(Query query) {
        try {
            KustoResultSetTable table = client.executeQuery(database, query.text(), query.properties()).getPrimaryResults();
            if (table == null) {
                LOG.error("Gold query {} returned no primary result table.", query.properties().getClientRequestId());
                throw new GoldStore.Unavailable();
            }
            List<String> results = new ArrayList<>();
            while (table.next()) {
                String json = table.getString("ResourceJson");
                if (json == null || json.isBlank()) {
                    LOG.error("Gold query {} returned an empty resource.", query.properties().getClientRequestId());
                    throw new GoldStore.Unavailable();
                }
                results.add(json);
            }
            return results;
        } catch (DataClientException | DataServiceException | KustoServiceQueryError e) {
            // Error text can include query parameters or clinical payloads.
            LOG.error("Gold query {} failed: {}", query.properties().getClientRequestId(), e.getClass().getSimpleName());
            throw new GoldStore.Unavailable();
        }
    }
}
