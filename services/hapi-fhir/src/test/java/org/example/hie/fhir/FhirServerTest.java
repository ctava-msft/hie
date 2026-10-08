package org.example.hie.fhir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FhirServerTest {
    private final FhirContext fhir = FhirContext.forR4Cached();
    private final GoldStore store = mock(GoldStore.class);
    private final HttpClient http = HttpClient.newHttpClient();
    private Server server;
    private String local;
    private String patient;

    @BeforeAll
    void start() throws Exception {
        var bundle = fhir.newJsonParser().parseResource(Bundle.class, Files.readString(
                Path.of(System.getProperty("epna.samples"), "01-admission.bundle.json")));
        patient = fhir.newJsonParser().encodeResourceToString(bundle.getEntryFirstRep().getResource());
        server = FhirServer.create(0, "https://api.example.org/fhir", store);
        server.start();
        local = "http://localhost:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void resetStore() {
        reset(store);
        when(store.search(anyString(), any())).thenReturn(List.of());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(local + path))
                .header("Accept", "application/fhir+json").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void outcome(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertInstanceOf(OperationOutcome.class, fhir.newJsonParser().parseResource(response.body()));
    }

    @Test
    void metadataAdvertisesOnlyImplementedResourcesAndInteractions() throws Exception {
        var response = get("/fhir/metadata");
        assertEquals(200, response.statusCode(), response.body());
        var statement = fhir.newJsonParser().parseResource(CapabilityStatement.class, response.body());
        assertEquals("4.0.1", statement.getFhirVersion().toCode());
        assertEquals(SearchParameters.SUPPORTED.keySet(), statement.getRestFirstRep().getResource()
                .stream().map(CapabilityStatement.CapabilityStatementRestResourceComponent::getType).collect(Collectors.toSet()));
        for (var resource : statement.getRestFirstRep().getResource()) {
            assertEquals(Set.of("read", "search-type"), resource.getInteraction().stream()
                    .map(interaction -> interaction.getCode().toCode()).collect(Collectors.toSet()));
            assertFalse(resource.hasProfile());
        }
        verifyNoInteractions(store);
    }

    @Test
    void readReturnsGoldResourceWithoutChangingItsJson() throws Exception {
        when(store.search(eq("Patient"), any())).thenReturn(List.of(patient));
        var response = get("/fhir/Patient/epna-p1");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(fhir.newJsonParser().encodeResourceToString(fhir.newJsonParser().parseResource(patient)),
                fhir.newJsonParser().encodeResourceToString(fhir.newJsonParser().parseResource(response.body())));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var parameters = ArgumentCaptor.forClass(SearchParameters.class);
        verify(store).search(eq("Patient"), parameters.capture());
        assertEquals("epna-p1", parameters.getValue().values().get("_id"));
    }

    @Test
    void everySyntheticResourceUpdateRoundTripsThroughRealHapiHttp() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, String> current = new HashMap<>();
        when(store.search(anyString(), any())).thenAnswer(invocation -> {
            SearchParameters parameters = invocation.getArgument(1);
            String found = current.get(invocation.<String>getArgument(0) + "/" + parameters.values().get("_id"));
            return found == null ? List.of() : List.of(found);
        });
        int checked = 0;
        try (var paths = Files.list(Path.of(System.getProperty("epna.samples")))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".bundle.json")).sorted().toList()) {
                for (var entry : json.readTree(Files.readString(path)).get("entry")) {
                    var resource = entry.get("resource");
                    String key = resource.get("resourceType").asText() + "/" + resource.get("id").asText();
                    current.put(key, resource.toString());
                    var response = get("/fhir/" + key);
                    assertEquals(200, response.statusCode(), response.body());
                    assertEquals(resource, json.readTree(response.body()), key);
                    checked++;
                }
            }
        }
        assertEquals(31, checked);
    }

    @Test
    void notFoundAndBackendFailuresAreNotEmptySuccessBundles() throws Exception {
        outcome(get("/fhir/Patient/epna-missing"), 404);
        when(store.search(anyString(), any())).thenThrow(new GoldStore.Unavailable());
        outcome(get("/fhir/Patient"), 503);
        outcome(get("/fhir/Patient/epna-p1"), 503);
        doThrow(new GoldStore.Unavailable()).when(store).checkReady();
        assertEquals(503, get("/health/ready").statusCode());
        assertEquals(200, get("/health/live").statusCode());
    }

    @Test
    void searchPagesUseGovernedLinksAndOmitInventedTotals() throws Exception {
        String second = patient.replace("epna-p1", "epna-p2");
        String third = patient.replace("epna-p1", "epna-p3");
        when(store.search(eq("Patient"), any())).thenAnswer(invocation -> {
            SearchParameters parameters = invocation.getArgument(1);
            return parameters.offset() == 0 ? List.of(patient, second, third) : List.of(third);
        });
        var firstResponse = get("/fhir/Patient?_count=2");
        assertEquals(200, firstResponse.statusCode(), firstResponse.body());
        var first = fhir.newJsonParser().parseResource(Bundle.class, firstResponse.body());
        assertEquals(Bundle.BundleType.SEARCHSET, first.getType());
        assertEquals(2, first.getEntry().size());
        assertFalse(first.hasTotal());
        assertEquals("https://api.example.org/fhir/Patient/epna-p1", first.getEntryFirstRep().getFullUrl());
        String next = first.getLink("next").getUrl();
        assertEquals("https://api.example.org/fhir/Patient?_count=2&_offset=2", next);
        var last = fhir.newJsonParser().parseResource(Bundle.class, get("/fhir/Patient?" + URI.create(next).getRawQuery()).body());
        assertEquals("epna-p3", last.getEntryFirstRep().getResource().getIdElement().getIdPart());
        assertNull(last.getLink("next"));
    }

    @Test
    void emptySearchIsAValidSearchset() throws Exception {
        var response = get("/fhir/Observation?patient=epna-p1&encounter=Encounter%2Fepna-e1");
        assertEquals(200, response.statusCode(), response.body());
        var bundle = fhir.newJsonParser().parseResource(Bundle.class, response.body());
        assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
        assertTrue(bundle.getEntry().isEmpty());
        var parameters = ArgumentCaptor.forClass(SearchParameters.class);
        verify(store).search(eq("Observation"), parameters.capture());
        assertEquals("Patient/epna-p1", parameters.getValue().values().get("patient"));
        assertEquals("Encounter/epna-e1", parameters.getValue().values().get("encounter"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/Patient?name=demo", "/Patient?_include=Patient:general-practitioner", "/Patient?_summary=count",
        "/Patient?_elements=id", "/Patient?_count=0", "/Patient?_count=101", "/Patient?_offset=-1",
        "/Patient?_offset=10001", "/Patient?_id=a,b", "/Patient?_id=a&_id=b", "/Patient?identifier=system%7C",
        "/Observation?patient.identifier=demo", "/Observation?code:not=foo", "/Observation?code=a,b",
        "/Observation?patient=https%3A%2F%2Fexample.org%2FPatient%2Fa",
        "/Observation?patient=Patient%2Fa%2F_history%2F1", "/Patient/epna-p1?_count=1",
        "/metadata?_count=1", "/Patient?_pretty=maybe"
    })
    void unsupportedSearchShapesFailExplicitly(String path) throws Exception {
        outcome(get("/fhir" + path), 400);
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void writesAndPostSearchAreRejected(String method) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(local + "/fhir/Patient/_search"))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        outcome(response, 405);
        verifyNoInteractions(store);
    }

    @Test
    void historyIsNotAdvertisedOrImplemented() throws Exception {
        var response = get("/fhir/Patient/epna-p1/_history/1");
        assertNotEquals(200, response.statusCode());
        assertInstanceOf(OperationOutcome.class, fhir.newJsonParser().parseResource(response.body()));
        verifyNoInteractions(store);
    }

    @Test
    void malformedOrWrongGoldResourcesFailClosed() throws Exception {
        when(store.search(anyString(), any())).thenReturn(List.of("not-json"));
        outcome(get("/fhir/Patient/epna-p1"), 502);
        when(store.search(anyString(), any())).thenReturn(List.of("{\"resourceType\":\"Encounter\",\"id\":\"epna-p1\"}"));
        outcome(get("/fhir/Patient/epna-p1"), 502);
        when(store.search(anyString(), any())).thenReturn(List.of(patient.replace("epna-p1", "epna-other")));
        outcome(get("/fhir/Patient/epna-p1"), 502);
    }

    @Test
    void declaredJsonOnlySurfaceHonorsAcceptQuality() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(local + "/fhir/metadata"))
                .header("Accept", "application/fhir+xml;q=1,application/fhir+json;q=0.5")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("json"));
        var refused = http.send(HttpRequest.newBuilder(URI.create(local + "/fhir/metadata"))
                .header("Accept", "application/fhir+json;q=0,*/*;q=1").GET().build(), HttpResponse.BodyHandlers.ofString());
        outcome(refused, 406);
        outcome(get("/fhir/metadata?_format=xml"), 406);
    }

    @Test
    void pagingWindowOverflowDoesNotAdvertiseAnUnusableNextPage() throws Exception {
        when(store.search(anyString(), any())).thenReturn(List.of(patient, patient));
        outcome(get("/fhir/Patient?_count=1&_offset=10000"), 422);
    }
}
