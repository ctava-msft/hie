package com.example.activator;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredential;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EnvironmentConfigurationTest {
    @TempDir Path directory;

    @Test
    void loadsDownstreamAndAzureCredentialsFromDotEnv() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, """
                WEBHOOK_API_KEY=webhook-test-key-with-at-least-32-characters
                ADMIN_API_KEY=admin-test-key-with-at-least-32-characters
                SERVICE_BUS_NAMESPACE=test.servicebus.windows.net
                DOWNSTREAM_URL=https://configured.example.test/delivery
                AZURE_TENANT_ID=11111111-1111-1111-1111-111111111111
                AZURE_CLIENT_ID=22222222-2222-2222-2222-222222222222
                AZURE_CLIENT_SECRET=synthetic-test-secret-not-a-real-credential
                """);
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class, BrokerConfiguration.class)
                .withPropertyValues("spring.config.import=" + file.toUri() + "[.properties]")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppProperties.class).downstreamUrl())
                            .isEqualTo(URI.create("https://configured.example.test/delivery"));
                    assertThat(context.getBean(TokenCredential.class)).isInstanceOf(ClientSecretCredential.class);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {}
}
