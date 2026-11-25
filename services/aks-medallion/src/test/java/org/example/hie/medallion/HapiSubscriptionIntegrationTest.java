package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

@EnabledIfEnvironmentVariable(named = "HIE_TEST_HAPI_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+/fhir")
@SpringBootTest(classes = HapiSubscriptionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class HapiSubscriptionIntegrationTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @LocalServerPort
    int port;
    @Autowired
    QueuePublisher publisher;

    @Test
    void hapiDeliversResourceToRelay() throws Exception {
        String base = System.getenv("HIE_TEST_HAPI_URL");
        String id = "test-" + UUID.randomUUID();
        var client = HttpClient.newHttpClient();
        String subscription = """
                {"resourceType":"Subscription","status":"active","reason":"synthetic relay test",
                 "criteria":"Patient?active=true","channel":{"type":"rest-hook",
                 "endpoint":"http://host.docker.internal:%d/internal/fhir-subscription",
                 "payload":"application/fhir+json",
                 "header":["Authorization: Bearer %s","X-Hie-Subscription: %s"]}}
                """.formatted(port, TOKEN, id);
        String location = post(client, base + "/Subscription", subscription)
                .headers().firstValue("location").orElseThrow().replaceAll("/_history/[^/]+$", "");
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            int attempt = 0;
            while (Mockito.mockingDetails(publisher).getInvocations().isEmpty()
                    && System.nanoTime() < deadline) {
                post(client, base + "/Patient", """
                        {"resourceType":"Patient","active":true,
                         "identifier":[{"system":"https://example.invalid/test-eid","value":"%s"}]}
                        """.formatted(id + "-" + attempt++));
                Thread.sleep(1500);
            }
            verify(publisher, timeout(1000).atLeastOnce()).publish(
                    Mockito.eq("test-queue"), anyString(), anyString(), any());
        } finally {
            var request = HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofSeconds(30))
                    .DELETE().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                    "HAPI subscription cleanup returned HTTP " + response.statusCode());
        }
    }

    private static HttpResponse<Void> post(HttpClient client, String url, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "HAPI returned HTTP " + response.statusCode());
        return response;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class TestApplication {
        @Bean
        QueuePublisher queuePublisher() {
            return Mockito.mock(QueuePublisher.class);
        }

        @Bean
        SubscriptionRelay subscriptionRelay(QueuePublisher publisher, ObjectMapper json) {
            return new SubscriptionRelay(publisher, json, "subscription-relay", TOKEN, "test-queue", 131072);
        }
    }
}
