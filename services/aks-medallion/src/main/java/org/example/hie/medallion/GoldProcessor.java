package org.example.hie.medallion;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.example.hie.medallion.StageRecords.GoldRecord;
import org.example.hie.medallion.StageRecords.SilverRecord;
import org.example.hie.medallion.StageRecords.StagePointer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class GoldProcessor {
    private static final String ADX_SCOPE = "https://api.kusto.windows.net/.default";
    private final MedallionProperties properties;
    private final StorageSupport storage;
    private final ObjectMapper json;
    private final TokenCredential credential;
    private final HttpClient http;
    private final ObjectProvider<PostgresGoldStore> postgres;
    private final ObjectProvider<GoldHapiClient> hapi;

    GoldProcessor(MedallionProperties properties, StorageSupport storage, ObjectMapper json,
                  TokenCredential credential, HttpClient http, ObjectProvider<PostgresGoldStore> postgres,
                  ObjectProvider<GoldHapiClient> hapi) {
        this.properties = properties;
        this.storage = storage;
        this.json = json;
        this.credential = credential;
        this.http = http;
        this.postgres = postgres;
        this.hapi = hapi;
    }

    void process(StagePointer pointer) {
        SilverRecord silver = storage.readJson(
                properties.silverContainer(), pointer.blobName(), SilverRecord.class);
        if (!pointer.eventId().equals(silver.eventId())) {
            throw new PermanentStageException("SilverIdentityMismatch", null);
        }
        GoldRecord gold = new GoldRecord(
                "1.0", silver.eventId(), silver.source(), silver.eventType(), silver.eventTime(),
                Instant.now(), pointer.blobName(), pointer.payloadHash(), silver.payload());
        if (properties.goldBackend().equals("postgresql") || properties.goldBackend().equals("postgresql-hapi")) {
            PostgresGoldStore store = postgres.getIfAvailable();
            if (store == null) {
                throw new IllegalStateException("PostgreSQL Gold store is not configured");
            }
            store.commit(gold);
            if (properties.goldBackend().equals("postgresql-hapi")) {
                GoldHapiClient client = hapi.getIfAvailable();
                if (client == null) {
                    throw new IllegalStateException("HAPI Gold client is not configured");
                }
                store.synchronizeCurrent(gold.payload().path("resourceType").asText(),
                        gold.payload().path("id").asText(), client.client());
            }
            return;
        }
        try {
            if (properties.adxIngestionUrl().isBlank()) {
                throw new IllegalStateException("app.adx-ingestion-url is required");
            }
            AccessToken token = credential.getToken(new TokenRequestContext().addScopes(ADX_SCOPE)).block();
            if (token == null) {
                throw new IllegalStateException("ADX token was not returned");
            }
            String endpoint = properties.adxIngestionUrl() + "/v1/rest/ingest/"
                    + properties.adxDatabase() + "/" + properties.goldTable() + "?streamFormat=json";
            var request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token.getToken())
                    .header("Content-Type", "application/json")
                    .header("x-ms-client-request-id", gold.eventId())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(gold)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ADX ingestion returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ADX ingestion interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Gold projection failed", exception);
        }
    }
}
