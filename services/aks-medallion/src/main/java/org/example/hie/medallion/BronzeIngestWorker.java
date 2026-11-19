package org.example.hie.medallion;

import com.azure.core.credential.TokenCredential;
import com.azure.messaging.eventhubs.EventProcessorClient;
import com.azure.messaging.eventhubs.EventProcessorClientBuilder;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import org.example.hie.medallion.StageRecords.BronzeRecord;
import org.example.hie.medallion.StageRecords.StagePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "bronze")
class BronzeIngestWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(BronzeIngestWorker.class);
    private final EventProcessorClient processor;
    private volatile boolean running;

    BronzeIngestWorker(
            MedallionProperties properties,
            TokenCredential credential,
            BlobServiceClient blobService,
            BlobServiceAsyncClient blobServiceAsync,
            StorageSupport storage,
            QueuePublisher publisher,
            ObjectMapper json) {
        BlobContainerAsyncClient checkpointContainer =
                blobServiceAsync.getBlobContainerAsyncClient(properties.checkpointContainer());
        processor = new EventProcessorClientBuilder()
                .fullyQualifiedNamespace(properties.eventHubsNamespace())
                .eventHubName(properties.eventHubName())
                .consumerGroup(properties.eventHubConsumerGroup())
                .credential(credential)
                .checkpointStore(new BlobCheckpointStore(checkpointContainer))
                .processEvent(context -> {
                    byte[] body = context.getEventData().getBody();
                    if (body.length > properties.maxMessageBytes()) {
                        throw new IllegalArgumentException("Event exceeds configured maximum size");
                    }
                    var metadata = new HashMap<String, String>();
                    context.getEventData().getProperties().forEach(
                            (key, value) -> metadata.put(key, String.valueOf(value)));
                    String eventId = firstNonBlank(
                            (String) metadata.get("eventId"),
                            context.getEventData().getMessageId(),
                            properties.eventHubName() + "-" + context.getPartitionContext().getPartitionId()
                                    + "-" + context.getEventData().getSequenceNumber());
                    Instant now = Instant.now();
                    Instant eventTime = context.getEventData().getEnqueuedTime();
                    String day = DateTimeFormatter.ISO_INSTANT.format(eventTime).substring(0, 10);
                    String blobName = "source=" + safePath(firstNonBlank(metadata.get("source"), "unknown"))
                            + "/date=" + day + "/" + safePath(eventId) + ".json";
                    String hash = StorageSupport.sha256(body);
                    var bronze = new BronzeRecord(
                            "1.0", eventId, metadata.getOrDefault("source", "unknown"),
                            metadata.getOrDefault("eventType", "unknown"),
                            context.getEventData().getContentType(), eventTime, now,
                            context.getPartitionContext().getPartitionId(),
                            context.getEventData().getSequenceNumber(),
                            context.getEventData().getOffset(),
                            hash, Base64.getEncoder().encodeToString(body), metadata);
                    storage.writeJson(properties.bronzeContainer(), blobName, bronze);
                    publisher.publish(properties.bronzeQueue(), eventId,
                            new StagePointer("1.0", eventId, blobName, hash));
                    context.updateCheckpoint();
                    LOG.info("Bronze committed eventId={} bytes={}", eventId, body.length);
                })
                .processError(error -> LOG.error("Event Hubs processor failure error={}",
                        error.getThrowable().getClass().getSimpleName()))
                .buildEventProcessorClient();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safePath(String value) {
        return value.replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    @Override
    public void start() {
        processor.start();
        running = true;
    }

    @Override
    public void stop() {
        processor.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
