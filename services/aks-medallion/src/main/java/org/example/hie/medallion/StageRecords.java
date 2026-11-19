package org.example.hie.medallion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

final class StageRecords {
    private StageRecords() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BronzeRecord(
            String schemaVersion,
            String eventId,
            String source,
            String eventType,
            String contentType,
            Instant eventTime,
            Instant ingestionTime,
            String partitionId,
            Long sequenceNumber,
            Long offset,
            String payloadHash,
            String payloadBase64,
            Map<String, String> properties) {
    }

    record StagePointer(String schemaVersion, String eventId, String blobName, String payloadHash) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SilverRecord(
            String schemaVersion,
            String eventId,
            String source,
            String eventType,
            String bronzeBlob,
            String bronzeHash,
            Instant eventTime,
            Instant normalizedTime,
            String normalizerVersion,
            JsonNode payload) {
    }

    record GoldRecord(
            String schemaVersion,
            String eventId,
            String source,
            String eventType,
            Instant eventTime,
            Instant projectedTime,
            String silverBlob,
            String silverHash,
            JsonNode payload) {
    }
}
