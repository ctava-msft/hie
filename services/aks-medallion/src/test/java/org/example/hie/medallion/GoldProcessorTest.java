package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azure.core.credential.TokenCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Instant;
import org.example.hie.medallion.StageRecords.GoldRecord;
import org.example.hie.medallion.StageRecords.SilverRecord;
import org.example.hie.medallion.StageRecords.StagePointer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class GoldProcessorTest {
    @Test
    void postgresBackendCommitsSilverResourceAndPropagatesFailures() throws Exception {
        var properties = new MedallionProperties("gold", "eventhub.example", "events", "group",
                "servicebus.example", "bronze-ready", "silver-ready", "https://storage.example",
                "bronze", "silver", "checkpoints", "quarantine", "postgresql", "", "hie_gold",
                "GoldEvent", 1024, 1);
        var storage = mock(StorageSupport.class);
        var postgres = mock(PostgresGoldStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresGoldStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(postgres);
        var json = new ObjectMapper();
        var payload = json.readTree("{\"resourceType\":\"Patient\",\"id\":\"p1\"}");
        var time = Instant.parse("2026-01-01T00:00:00Z");
        var silver = new SilverRecord("1.0", "event-1", "epic", "ADT", "bronze.json",
                "raw-hash", time, time, "normalizer", payload);
        var pointer = new StagePointer("1.0", "event-1", "silver.json", "silver-hash");
        when(storage.readJson("silver", "silver.json", SilverRecord.class)).thenReturn(silver);
        var processor = new GoldProcessor(properties, storage, json, mock(TokenCredential.class),
                mock(HttpClient.class), provider, hapiProvider());

        processor.process(pointer);
        verify(postgres).commit(argThat((GoldRecord record) ->
                record.eventId().equals("event-1") && record.payload().equals(payload)
                        && record.silverBlob().equals("silver.json")));
        doThrow(new IllegalStateException("database failure")).when(postgres).commit(any());
        assertThrows(IllegalStateException.class, () -> processor.process(pointer));
    }

    @Test
    void rejectsMismatchedSilverIdentityBeforeCommitting() {
        var properties = new MedallionProperties("gold", "eventhub.example", "events", "group",
                "servicebus.example", "bronze-ready", "silver-ready", "https://storage.example",
                "bronze", "silver", "checkpoints", "quarantine", "postgresql", "", "hie_gold",
                "GoldEvent", 1024, 1);
        var storage = mock(StorageSupport.class);
        var postgres = mock(PostgresGoldStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresGoldStore> provider = mock(ObjectProvider.class);
        var silver = new SilverRecord("1.0", "other", "epic", "ADT", "bronze.json",
                "hash", Instant.now(), Instant.now(), "normalizer", new ObjectMapper().createObjectNode());
        when(storage.readJson("silver", "silver.json", SilverRecord.class)).thenReturn(silver);
        var processor = new GoldProcessor(properties, storage, new ObjectMapper(),
                mock(TokenCredential.class), mock(HttpClient.class), provider, hapiProvider());
        assertThrows(PermanentStageException.class, () -> processor.process(
                new StagePointer("1.0", "event-1", "silver.json", "hash")));
        verifyNoInteractions(postgres, provider);
    }

    @Test
    void hapiSynchronizationFailureFailsStageAfterProjectionCommit() throws Exception {
        var properties = new MedallionProperties("gold", "eventhub.example", "events", "group",
                "servicebus.example", "bronze-ready", "silver-ready", "https://storage.example",
                "bronze", "silver", "checkpoints", "quarantine", "postgresql-hapi", "", "hie_gold",
                "GoldEvent", 1024, 1);
        var storage = mock(StorageSupport.class);
        var store = mock(PostgresGoldStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PostgresGoldStore> postgres = mock(ObjectProvider.class);
        var hapi = mock(GoldHapiClient.class);
        var hapiProvider = hapiProvider();
        when(postgres.getIfAvailable()).thenReturn(store);
        when(hapiProvider.getIfAvailable()).thenReturn(hapi);
        var client = mock(HapiVersionedClient.class);
        when(hapi.client()).thenReturn(client);
        doThrow(new IllegalStateException("unavailable")).when(store).synchronizeCurrent("Patient", "p1", client);
        var json = new ObjectMapper();
        var time = Instant.parse("2026-01-01T00:00:00Z");
        var silver = new SilverRecord("1", "event-1", "test", "test", "bronze.json",
                "hash", time, time, "normalizer", json.readTree("{\"resourceType\":\"Patient\",\"id\":\"p1\"}"));
        when(storage.readJson("silver", "silver.json", SilverRecord.class)).thenReturn(silver);
        var processor = new GoldProcessor(properties, storage, json, mock(TokenCredential.class),
                mock(HttpClient.class), postgres, hapiProvider);
        assertThrows(IllegalStateException.class, () -> processor.process(
                new StagePointer("1", "event-1", "silver.json", "hash")));
        verify(store).commit(any());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GoldHapiClient> hapiProvider() {
        return mock(ObjectProvider.class);
    }
}
