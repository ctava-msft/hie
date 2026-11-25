package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.example.hie.medallion.StageRecords.BronzeRecord;
import org.example.hie.medallion.StageRecords.StagePointer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class SilverProcessorTest {
    @Test
    void rejectsUnsupportedPayload() {
        var properties = properties();
        var storage = Mockito.mock(StorageSupport.class);
        var publisher = Mockito.mock(QueuePublisher.class);
        var json = new ObjectMapper().registerModule(new JavaTimeModule());
        @SuppressWarnings("unchecked")
        ObjectProvider<SilverHapiMdm> mdm = Mockito.mock(ObjectProvider.class);
        var processor = new SilverProcessor(properties, storage, publisher, json, mdm, false);
        var body = Base64.getEncoder().encodeToString("not-json".getBytes());
        var bronze = new BronzeRecord("1.0", "event-1", "test", "ADT", "text/plain",
                Instant.now(), Instant.now(), "0", 1L, 0L, StorageSupport.sha256("raw".getBytes()),
                body, Map.of());
        when(storage.readJson("bronze", "bronze/event.json", BronzeRecord.class)).thenReturn(bronze);

        assertThrows(PermanentStageException.class,
                () -> processor.process(new StagePointer("1.0", "event-1",
                        "bronze/event.json", bronze.payloadHash())));
    }

    @Test
    void holdsUnresolvedPatientBeforePublishing() throws Exception {
        var storage = Mockito.mock(StorageSupport.class);
        var publisher = Mockito.mock(QueuePublisher.class);
        var client = Mockito.mock(SilverHapiMdm.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SilverHapiMdm> mdm = Mockito.mock(ObjectProvider.class);
        var json = new ObjectMapper().registerModule(new JavaTimeModule());
        var payload = json.readTree("{\"resourceType\":\"Patient\",\"id\":\"p1\"}");
        var body = Base64.getEncoder().encodeToString(json.writeValueAsBytes(payload));
        var bronze = new BronzeRecord("1.0", "event-1", "test", "ADT", "application/fhir+json",
                Instant.now(), Instant.now(), "0", 1L, 0L, "hash", body, Map.of());
        when(storage.readJson("bronze", "bronze/event.json", BronzeRecord.class)).thenReturn(bronze);
        when(mdm.getIfAvailable()).thenReturn(client);
        when(client.normalize("test", payload)).thenThrow(new PermanentStageException("AmbiguousPatient", null));
        var processor = new SilverProcessor(properties(), storage, publisher, json, mdm, true);

        assertThrows(PermanentStageException.class, () -> processor.process(
                new StagePointer("1.0", "event-1", "bronze/event.json", "hash")));
        verify(storage, never()).writeJson(anyString(), anyString(), any());
        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    private static MedallionProperties properties() {
        return new MedallionProperties("silver", "eventhub.example", "events", "group",
                "servicebus.example", "bronze-ready", "silver-ready", "https://storage.example",
                "bronze", "silver", "checkpoints", "quarantine", "adx", "", "hie_gold",
                "GoldEvent", 1024, 1);
    }
}
