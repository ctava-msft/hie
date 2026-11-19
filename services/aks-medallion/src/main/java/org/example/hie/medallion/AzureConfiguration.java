package org.example.hie.medallion;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AzureConfiguration {
    @Bean
    TokenCredential tokenCredential() {
        return new DefaultAzureCredentialBuilder().build();
    }

    @Bean
    ServiceBusClientBuilder serviceBusClientBuilder(
            MedallionProperties properties, TokenCredential credential) {
        return new ServiceBusClientBuilder()
                .fullyQualifiedNamespace(properties.serviceBusNamespace())
                .credential(credential);
    }

    @Bean
    BlobServiceClient blobServiceClient(MedallionProperties properties, TokenCredential credential) {
        if (properties.storageAccountUrl().isBlank()) {
            throw new IllegalStateException("app.storage-account-url is required");
        }
        return new BlobServiceClientBuilder()
                .endpoint(properties.storageAccountUrl())
                .credential(credential)
                .buildClient();
    }

    @Bean
    BlobServiceAsyncClient blobServiceAsyncClient(
            MedallionProperties properties, TokenCredential credential) {
        if (properties.storageAccountUrl().isBlank()) {
            throw new IllegalStateException("app.storage-account-url is required");
        }
        return new BlobServiceClientBuilder()
                .endpoint(properties.storageAccountUrl())
                .credential(credential)
                .buildAsyncClient();
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
