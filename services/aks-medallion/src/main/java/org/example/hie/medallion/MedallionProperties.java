package org.example.hie.medallion;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record MedallionProperties(
        @NotBlank String role,
        @NotBlank String eventHubsNamespace,
        @NotBlank String eventHubName,
        String eventHubConsumerGroup,
        @NotBlank String serviceBusNamespace,
        String bronzeQueue,
        String silverQueue,
        String storageAccountUrl,
        String bronzeContainer,
        String silverContainer,
        String checkpointContainer,
        String quarantineContainer,
        String goldBackend,
        String adxIngestionUrl,
        String adxDatabase,
        String goldTable,
        int maxMessageBytes,
        int workerConcurrency) {
    public MedallionProperties {
        eventHubConsumerGroup = eventHubConsumerGroup == null || eventHubConsumerGroup.isBlank()
                ? "aks-medallion" : eventHubConsumerGroup;
        bronzeQueue = bronzeQueue == null || bronzeQueue.isBlank() ? "bronze-ready" : bronzeQueue;
        silverQueue = silverQueue == null || silverQueue.isBlank() ? "silver-ready" : silverQueue;
        storageAccountUrl = storageAccountUrl == null ? "" : storageAccountUrl;
        bronzeContainer = bronzeContainer == null || bronzeContainer.isBlank() ? "bronze" : bronzeContainer;
        silverContainer = silverContainer == null || silverContainer.isBlank() ? "silver" : silverContainer;
        checkpointContainer = checkpointContainer == null || checkpointContainer.isBlank()
                ? "eventhub-checkpoints" : checkpointContainer;
        quarantineContainer = quarantineContainer == null || quarantineContainer.isBlank()
                ? "quarantine" : quarantineContainer;
        goldBackend = goldBackend == null || goldBackend.isBlank() ? "adx" : goldBackend;
        if (!goldBackend.equals("adx") && !goldBackend.equals("postgresql")
                && !goldBackend.equals("postgresql-hapi")) {
            throw new IllegalArgumentException("app.gold-backend must be adx, postgresql or postgresql-hapi");
        }
        adxIngestionUrl = adxIngestionUrl == null ? "" : adxIngestionUrl;
        adxDatabase = adxDatabase == null || adxDatabase.isBlank() ? "hie_gold" : adxDatabase;
        goldTable = goldTable == null || goldTable.isBlank() ? "GoldEvent" : goldTable;
        maxMessageBytes = maxMessageBytes <= 0 ? 1_048_576 : maxMessageBytes;
        workerConcurrency = workerConcurrency <= 0 ? 4 : workerConcurrency;
    }
}
