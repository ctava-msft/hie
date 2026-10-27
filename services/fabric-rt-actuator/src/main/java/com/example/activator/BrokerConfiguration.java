package com.example.activator;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class BrokerConfiguration {
    @Bean
    TokenCredential azureCredential(Environment environment) {
        String secret = environment.getProperty("AZURE_CLIENT_SECRET");
        if (secret != null && !secret.isBlank()) {
            String tenant = environment.getProperty("AZURE_TENANT_ID");
            String client = environment.getProperty("AZURE_CLIENT_ID");
            if (tenant == null || tenant.isBlank() || client == null || client.isBlank()) {
                throw new IllegalStateException("AZURE_CLIENT_SECRET requires AZURE_TENANT_ID and AZURE_CLIENT_ID");
            }
            return new ClientSecretCredentialBuilder().tenantId(tenant).clientId(client).clientSecret(secret).build();
        }
        return new DefaultAzureCredentialBuilder().build();
    }

    @Bean
    ServiceBusClientBuilder serviceBusBuilder(AppProperties properties, TokenCredential credential) {
        var builder = new ServiceBusClientBuilder()
                .retryOptions(new AmqpRetryOptions().setMaxRetries(2).setTryTimeout(Duration.ofSeconds(5)));
        if (properties.serviceBusConnectionString() != null
                && !properties.serviceBusConnectionString().isBlank()) {
            return builder.connectionString(properties.serviceBusConnectionString());
        }
        return builder.credential(properties.serviceBusNamespace(), credential);
    }

    @Bean(destroyMethod = "close")
    ServiceBusSenderClient sender(ServiceBusClientBuilder builder, AppProperties properties) {
        return builder.sender().queueName(properties.serviceBusQueue()).buildClient();
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
