package com.example.activator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.azure.core.credential.TokenCredential;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "app.webhook-api-key=" + TestProperties.WEBHOOK_KEY,
        "app.admin-api-key=" + TestProperties.ADMIN_KEY,
        "app.service-bus-namespace=test.servicebus.windows.net",
        "app.service-bus-connection-string=",
        "app.worker-enabled=false",
        "app.audit-directory=target/application-test-audit"
})
class ApplicationTest {
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @MockitoBean EventPublisher publisher;
    @MockitoBean TokenCredential credential;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void actualServletFilterRejectsUnauthenticatedAndCrossRoleRequests() throws Exception {
        var webhook = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/activator"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(401, http.send(webhook, HttpResponse.BodyHandlers.discarding()).statusCode());

        var management = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rules/definition"))
                .header("X-API-Key", TestProperties.WEBHOOK_KEY).GET().build();
        assertEquals(401, http.send(management, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void receivesWebhookThroughRunningServer() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/activator"))
                .header("Content-Type", "application/json").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                .header("Idempotency-Key", "integration-event")
                .POST(HttpRequest.BodyPublishers.ofString("{\"synthetic\":true}")).build();
        assertEquals(202, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        verify(publisher).publish(eq("integration-event"), any(byte[].class));
    }

    @Test
    void healthUsesSeparateManagementPort() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort
                + "/actuator/health/readiness")).GET().build();
        assertEquals(200, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        var mainPort = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/actuator/health/readiness")).GET().build();
        assertEquals(404, http.send(mainPort, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
}
