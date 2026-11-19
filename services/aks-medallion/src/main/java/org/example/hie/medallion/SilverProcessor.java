package org.example.hie.medallion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import org.example.hie.medallion.StageRecords.BronzeRecord;
import org.example.hie.medallion.StageRecords.SilverRecord;
import org.example.hie.medallion.StageRecords.StagePointer;
import org.springframework.stereotype.Component;

@Component
class SilverProcessor {
    static final String NORMALIZER_VERSION = "aks-json-normalizer-0.1";
    private final MedallionProperties properties;
    private final StorageSupport storage;
    private final QueuePublisher publisher;
    private final ObjectMapper json;

    SilverProcessor(MedallionProperties properties, StorageSupport storage,
                    QueuePublisher publisher, ObjectMapper json) {
        this.properties = properties;
        this.storage = storage;
        this.publisher = publisher;
        this.json = json;
    }

    void process(StagePointer pointer) {
        BronzeRecord bronze = storage.readJson(
                properties.bronzeContainer(), pointer.blobName(), BronzeRecord.class);
        if (!pointer.payloadHash().equals(bronze.payloadHash())) {
            throw new PermanentStageException("BronzeHashMismatch", null);
        }
        byte[] body = Base64.getDecoder().decode(bronze.payloadBase64());
        JsonNode payload;
        try {
            payload = json.readTree(body);
            if (!payload.isObject()) {
                throw new IllegalArgumentException("Only JSON objects are accepted");
            }
        } catch (Exception exception) {
            throw new PermanentStageException("UnsupportedPayload", exception);
        }
        String blobName = "event=" + safePath(bronze.eventId()) + ".json";
        SilverRecord silver = new SilverRecord(
                "1.0", bronze.eventId(), bronze.source(), bronze.eventType(), pointer.blobName(),
                bronze.payloadHash(), bronze.eventTime(), Instant.now(), NORMALIZER_VERSION, payload);
        storage.writeJson(properties.silverContainer(), blobName, silver);
        byte[] silverBytes;
        try {
            silverBytes = json.writeValueAsBytes(silver);
        } catch (Exception exception) {
            throw new IllegalStateException("Silver serialization failed", exception);
        }
        publisher.publish(properties.silverQueue(), bronze.eventId(),
                new StagePointer("1.0", bronze.eventId(), blobName, StorageSupport.sha256(silverBytes)));
    }

    private static String safePath(String value) {
        return value.replaceAll("[^A-Za-z0-9._:-]", "_");
    }
}
