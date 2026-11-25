package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SilverHapiMdmTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void holdsPossibleMatchInsteadOfPromotingObservation() throws Exception {
        HapiVersionedClient hapi = mock(HapiVersionedClient.class);
        when(hapi.read(startsWith("Patient/"))).thenReturn(json.readTree("""
                {"resourceType":"Patient","identifier":[{"system":"https://example.invalid/eid","value":"1"}]}
                """));
        when(hapi.read(startsWith("$mdm-query-links"))).thenReturn(json.readTree("""
                {"resourceType":"Parameters","parameter":[{"name":"link","part":[
                 {"name":"matchResult","valueString":"POSSIBLE_MATCH"}]}]}
                """));
        var mdm = new SilverHapiMdm(hapi, mock(DataSource.class), "https://example.invalid/eid");
        var error = assertThrows(PermanentStageException.class, () -> mdm.normalize("test", json.readTree("""
                {"resourceType":"Observation","id":"o1","meta":{"lastUpdated":"2026-01-01T00:00:00Z"},
                 "subject":{"reference":"Patient/p1"}}
                """)));
        assertEquals("AmbiguousPatient", error.reason());
    }

    @Test
    void holdsMissingOrUnsupportedPatientReferences() throws Exception {
        var mdm = new SilverHapiMdm(mock(HapiVersionedClient.class), mock(DataSource.class),
                "https://example.invalid/eid");
        var error = assertThrows(PermanentStageException.class, () -> mdm.normalize("test", json.readTree("""
                {"resourceType":"Observation","id":"o1","meta":{"lastUpdated":"2026-01-01T00:00:00Z"}}
                """)));
        assertEquals("UnresolvedPatient", error.reason());
        var invalid = assertThrows(PermanentStageException.class, () -> mdm.normalize("test", json.readTree("""
                {"resourceType":"Observation","id":"o1","meta":{"lastUpdated":"2026-01-01T00:00:00Z"},
                 "subject":{"reference":"http://example.invalid/Patient/1"}}
                """)));
        assertEquals("UnsupportedPatientReference", invalid.reason());
    }
}
