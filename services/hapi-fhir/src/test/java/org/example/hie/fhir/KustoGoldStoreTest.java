package org.example.hie.fhir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.ClientRequestProperties;
import com.microsoft.azure.kusto.data.KustoOperationResult;
import com.microsoft.azure.kusto.data.exceptions.KustoServiceQueryError;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KustoGoldStoreTest {
    @Test
    void exportAllResourceQueryContractsForKqlAnalysis() throws Exception {
        List<Map<String, String>> contracts = new ArrayList<>();
        for (var resource : SearchParameters.SUPPORTED.entrySet()) {
            Map<String, String[]> values = new HashMap<>();
            for (String name : resource.getValue().keySet()) {
                String value = switch (name) {
                    case "_id" -> "epna-example";
                    case "patient" -> "Patient/epna-p1";
                    case "encounter" -> "Encounter/epna-e1";
                    case "status" -> resource.getKey().equals("Encounter") ? "in-progress" : "final";
                    case "identifier" -> "https://example.org/demo|SYNTHETIC-001";
                    case "code" -> "http://loinc.org|59408-5";
                    case "clinical-status" -> "active";
                    default -> throw new IllegalArgumentException("Missing test contract for " + name);
                };
                values.put(name, new String[] {value});
            }
            var query = KustoGoldStore.query(resource.getKey(), SearchParameters.parse(resource.getKey(), values));
            contracts.add(Map.of("resourceType", resource.getKey(), "query", query.text()));
        }
        assertEquals(7, contracts.size());
        Files.writeString(Path.of("target", "kql-query-contracts.json"), new ObjectMapper().writeValueAsString(contracts));
    }

    @Test
    void userTokensAreBoundAndNeverBecomeKql() {
        String hostile = "https://example.org/terminology|value'; print 'not-a-query";
        var parameters = SearchParameters.parse("Observation", Map.of(
                "code", new String[] {hostile}, "patient", new String[] {"epna-p1"},
                "status", new String[] {"final"}, "_count", new String[] {"1"}));
        var query = KustoGoldStore.query("Observation", parameters);
        assertFalse(query.text().contains(hostile));
        assertFalse(query.text().contains("not-a-query"));
        assertNotNull(query.properties().getParameter("p_code_value"));
        assertTrue(query.text().contains("declare query_parameters("));
        assertTrue(query.text().contains("mv-apply SearchCoding = ResourceJson.code.coding"));
        assertTrue(query.text().indexOf("GoldFhirCurrent()") < query.text().indexOf("where tostring(ResourceJson.status)"));
        assertTrue(query.text().contains("| take p_limit"));
        assertEquals(20_000L, query.properties().getTimeoutInMilliSec());
    }

    @Test
    void identifiersAndClinicalStatusUseTheirOwnCodingArrays() {
        var patient = KustoGoldStore.query("Patient", SearchParameters.parse("Patient",
                Map.of("identifier", new String[] {"|SYNTHETIC-001"})));
        assertTrue(patient.text().contains("ResourceJson.identifier"));
        assertTrue(patient.text().contains("SearchCoding.value"));
        var condition = KustoGoldStore.query("Condition", SearchParameters.parse("Condition",
                Map.of("clinical-status", new String[] {"active"}, "code", new String[] {"demo|pneumonia"})));
        assertTrue(condition.text().contains("ResourceJson.clinicalStatus.coding"));
        assertTrue(condition.text().contains("p_clinical_status_has_system"));
        assertTrue(condition.text().contains("p_code_has_system"));
    }

    @Test
    void sdkQueryExecutionPreservesJsonAndUsesQueryNotManagementEndpoint() throws Exception {
        Client client = mock(Client.class);
        String resource = "{\"resourceType\":\"Patient\",\"id\":\"epna-p1\"}";
        var frames = List.of(
                Map.of("FrameType", "DataTable", "TableId", 0, "TableKind", "PrimaryResult", "TableName", "PrimaryResult",
                        "Columns", List.of(Map.of("ColumnName", "ResourceJson", "ColumnType", "string")),
                        "Rows", List.of(List.of(resource))),
                Map.of("FrameType", "DataSetCompletion", "HasErrors", false));
        var result = new KustoOperationResult(new ObjectMapper().writeValueAsString(frames), "v2");
        when(client.executeQuery(eq("hie_gold"), anyString(), any())).thenReturn(result);
        var actual = new KustoGoldStore(client, "hie_gold").search("Patient", SearchParameters.parse("Patient", Map.of()));
        assertEquals(List.of(resource), actual);
        var properties = ArgumentCaptor.forClass(ClientRequestProperties.class);
        verify(client).executeQuery(eq("hie_gold"), startsWith("declare query_parameters"), properties.capture());
        assertEquals(false, properties.getValue().getOption("deferpartialqueryfailures"));
    }

    @Test
    void http200PartialQueryFailuresCannotBeMistakenForResults() throws Exception {
        String partial = """
                [{"FrameType":"DataTable","TableId":0,"TableKind":"PrimaryResult","TableName":"PrimaryResult",
                  "Columns":[{"ColumnName":"ResourceJson","ColumnType":"string"}],"Rows":[]},
                 {"FrameType":"DataSetCompletion","HasErrors":true,"OneApiErrors":[]}]
                """;
        Client client = mock(Client.class);
        when(client.executeQuery(anyString(), anyString(), any()))
                .thenAnswer(invocation -> new KustoOperationResult(partial, "v2"));
        var store = new KustoGoldStore(client, "hie_gold");
        assertThrows(GoldStore.Unavailable.class, () -> store.search("Patient", SearchParameters.parse("Patient", Map.of())));
    }

    @Test
    void missingPrimaryResultIsNotAnEmptySearch() throws Exception {
        Client client = mock(Client.class);
        when(client.executeQuery(anyString(), anyString(), any()))
                .thenReturn(new KustoOperationResult("[{\"FrameType\":\"DataSetCompletion\",\"HasErrors\":false}]", "v2"));
        assertThrows(GoldStore.Unavailable.class, () -> new KustoGoldStore(client, "hie_gold").checkReady());
    }

    @Test
    void upstreamMessagesAreNotExposedToFhirClients() throws Exception {
        Client client = mock(Client.class);
        when(client.executeQuery(anyString(), anyString(), any()))
                .thenThrow(new KustoServiceQueryError("sensitive-query-parameter"));
        var failure = assertThrows(GoldStore.Unavailable.class, () -> new KustoGoldStore(client, "hie_gold").checkReady());
        assertFalse(failure.getMessage().contains("sensitive"));
        assertNull(failure.getCause());
    }
}
