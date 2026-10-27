package com.example.activator;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.credential.AccessToken;
import com.azure.core.exception.AzureException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FabricClient {
    private static final URI ORIGIN = URI.create("https://api.fabric.microsoft.com");
    private final HttpClient http;
    private final TokenCredential credential;
    private final ObjectMapper mapper;
    private final AppProperties properties;

    public FabricClient(HttpClient http, TokenCredential credential, ObjectMapper mapper, AppProperties properties) {
        this.http = http;
        this.credential = credential;
        this.mapper = mapper;
        this.properties = properties;
    }

    public JsonNode getDefinition() throws IOException {
        JsonNode result = execute("getDefinition", mapper.createObjectNode(), true);
        if (result == null || !result.isObject() || !result.path("definition").path("parts").isArray()) {
            throw new IOException("Fabric returned an invalid definition envelope");
        }
        return result;
    }

    public void updateDefinition(JsonNode definition) throws IOException {
        execute("updateDefinition", definition, false);
    }

    private JsonNode execute(String action, JsonNode body, boolean resultRequired) throws IOException {
        String workspace = uuid(properties.fabricWorkspaceId());
        String activator = uuid(properties.fabricActivatorId());
        URI uri = ORIGIN.resolve("/v1/workspaces/" + workspace + "/reflexes/" + activator + "/" + action);
        Instant deadline = Instant.now().plusSeconds(180);
        try {
            var response = send(uri, mapper.writeValueAsString(body), deadline);
            if (response.statusCode() == 200) {
                return resultRequired ? mapper.readTree(response.body()) : mapper.createObjectNode();
            }
            if (response.statusCode() != 202) {
                throw failure(response.statusCode());
            }
            URI operation = operationUri(response);
            pause(response, deadline);
            while (true) {
                var status = send(operation, null, deadline);
                if (status.statusCode() == 429 || status.statusCode() == 202) {
                    pause(status, deadline);
                    continue;
                }
                if (status.statusCode() != 200) {
                    throw failure(status.statusCode());
                }
                String state = mapper.readTree(status.body()).path("status").asText();
                if ("Succeeded".equals(state)) {
                    if (!resultRequired) {
                        return mapper.createObjectNode();
                    }
                    var result = send(URI.create(operation + "/result"), null, deadline);
                    if (result.statusCode() != 200) {
                        throw failure(result.statusCode());
                    }
                    return mapper.readTree(result.body());
                }
                if (!"NotStarted".equals(state) && !"Running".equals(state)) {
                    throw new IOException("Fabric operation failed or returned an unknown status");
                }
                pause(status, deadline);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Fabric operation interrupted", exception);
        }
    }

    private HttpResponse<String> send(URI uri, String body, Instant deadline)
            throws IOException, InterruptedException {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new IOException("Fabric operation timed out; outcome may be unknown");
        }
        AccessToken token;
        try {
            token = credential.getToken(new TokenRequestContext()
                    .addScopes("https://api.fabric.microsoft.com/.default")).block(Duration.ofSeconds(20));
        } catch (AzureException exception) {
            throw new IOException("Fabric token acquisition failed", exception);
        }
        if (token == null) {
            throw new IOException("Fabric token acquisition returned no token");
        }
        var request = HttpRequest.newBuilder(uri)
                .timeout(remaining.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : remaining)
                .header("Authorization", "Bearer " + token.getToken())
                .header("Accept", "application/json");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static URI operationUri(HttpResponse<?> response) throws IOException {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location != null) {
            URI uri;
            try {
                uri = ORIGIN.resolve(location);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid Fabric operation location", exception);
            }
            if (!"https".equals(uri.getScheme()) || !"api.fabric.microsoft.com".equals(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getPath().matches("/v1/operations/[0-9a-fA-F-]{36}")) {
                throw new IOException("Untrusted Fabric operation location");
            }
            return uri;
        }
        String id = response.headers().firstValue("x-ms-operation-id")
                .orElseThrow(() -> new IOException("Fabric 202 response has no operation reference"));
        try {
            return ORIGIN.resolve("/v1/operations/" + UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Fabric operation ID", exception);
        }
    }

    private static void pause(HttpResponse<?> response, Instant deadline) throws IOException, InterruptedException {
        long seconds = 2;
        try {
            seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("2"));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Fabric Retry-After header", exception);
        }
        seconds = Math.max(1, seconds);
        if (Instant.now().plusSeconds(seconds).isAfter(deadline)) {
            throw new IOException("Fabric polling deadline exceeded; outcome may be unknown");
        }
        Thread.sleep(Duration.ofSeconds(seconds));
    }

    private static IOException failure(int status) {
        return new IOException("Fabric returned HTTP " + status + "; outcome may be unknown");
    }

    private static String uuid(String value) {
        try {
            return UUID.fromString(value == null ? "" : value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Configure valid FABRIC_WORKSPACE_ID and FABRIC_ACTIVATOR_ID", exception);
        }
    }
}
