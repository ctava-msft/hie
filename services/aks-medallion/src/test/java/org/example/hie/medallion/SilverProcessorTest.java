package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertThrows;
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

class SilverProcessorTest {
    @Test
    void rejectsUnsupportedPayload() {
        var properties = properties();
        var storage = Mockito.mock(StorageSupport.class);
        var publisher = Mockito.mock(QueuePublisher.class);
        var json = new ObjectMapper().registerModule(new JavaTimeModule());
        var processor = new SilverProcessor(properties, storage, publisher, json);
        var body = Base64.getEncoder().encodeToString("not-json".getBytes());
        var bronze = new BronzeRecord("1.0", "event-1", "test", "ADT", "text/plain",
                Instant.now(), Instant.now(), "0", 1L, 0L, StorageSupport.sha256("raw".getBytes()),
                body, Map.of());
        when(storage.readJson("bronze", "bronze/event.json", BronzeRecord.class)).thenReturn(bronze);

        assertThrows(PermanentStageException.class,
                () -> processor.process(new StagePointer("1.0", "event-1",
                        "bronze/event.json", bronze.payloadHash())));
    }

    private static MedallionProperties properties() {
        return new MedallionProperties("silver", "eventhub.example", "events", "group",
                "servicebus.example", "bronze-ready", "silver-ready", "https://storage.example",
                "bronze", "silver", "checkpoints", "quarantine", "", "hie_gold",
                "GoldEvent", 1024, 1);
    }
}
