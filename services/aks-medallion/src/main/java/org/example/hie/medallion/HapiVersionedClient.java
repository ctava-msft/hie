package org.example.hie.medallion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

final class HapiVersionedClient {
    private static final String VERSION = "urn:hie:source-version";
    private static final String HASH = "urn:hie:source-hash";
    private final HttpClient http;
    private final ObjectMapper json;
    private final String base;
    private final String token;

    HapiVersionedClient(HttpClient http, ObjectMapper json, String base, String token) {
        URI uri = URI.create(base);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (!"https".equals(uri.getScheme())
                && !("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())))
                || token == null || token.isBlank() || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException("Invalid HAPI client configuration");
        }
        this.http = http;
        this.json = json;
        this.base = base.replaceAll("/+$", "");
        this.token = token;
    }

    void write(ObjectNode source, Instant version) {
        String type = source.path("resourceType").asText("");
        String id = source.path("id").asText("");
        if (!type.matches("[A-Z][A-Za-z0-9]{0,63}") || !id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new PermanentStageException("InvalidFhirIdentity", null);
        }
        JsonNode extensions = source.get("extension");
        if (extensions != null && !extensions.isArray()) {
            throw new PermanentStageException("InvalidFhirExtensions", null);
        }
        if (extensions != null) {
            for (JsonNode extension : extensions) {
                if (VERSION.equals(extension.path("url").asText()) || HASH.equals(extension.path("url").asText())) {
                    throw new PermanentStageException("ReservedFhirExtension", null);
                }
            }
        }
        String hash = hash(source);
        ObjectNode payload = source.deepCopy();
        if (payload.path("meta").isObject()) {
            ObjectNode meta = (ObjectNode) payload.get("meta");
            meta.remove("lastUpdated");
            meta.remove("versionId");
        }
        ArrayNode out = payload.withArray("extension");
        out.addObject().put("url", VERSION).put("valueInstant", version.toString());
        out.addObject().put("url", HASH).put("valueString", hash);
        String endpoint = base + "/" + type + "/" + id;
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpResponse<String> current = send(request(endpoint).GET().build());
            String condition;
            if (current.statusCode() == 404) {
                condition = null;
            } else if (current.statusCode() == 200) {
                JsonNode existing;
                try {
                    existing = json.readTree(current.body());
                } catch (IOException exception) {
                    throw new IllegalStateException("HAPI returned invalid FHIR JSON", exception);
                }
                String existingVersion = extension(existing, VERSION, "valueInstant");
                String existingHash = extension(existing, HASH, "valueString");
                Instant previous;
                try {
                    previous = Instant.parse(existingVersion);
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("HAPI resource has invalid source version", exception);
                }
                if (previous.isAfter(version)) {
                    return;
                }
                if (previous.equals(version)) {
                    if (!hash.equals(existingHash)) {
                        throw new PermanentStageException("ConflictingFhirVersion", null);
                    }
                    return;
                }
                condition = current.headers().firstValue("ETag").orElseThrow(
                        () -> new IllegalStateException("HAPI response did not include ETag"));
            } else {
                throw new IllegalStateException("HAPI read returned HTTP " + current.statusCode());
            }
            HttpRequest.Builder builder = request(endpoint)
                    .header("Content-Type", "application/fhir+json");
            if (condition == null) {
                builder.header("If-Match", "W/\"0\"");
            } else {
                builder.header("If-Match", condition);
            }
            HttpResponse<String> response = send(builder.PUT(
                    HttpRequest.BodyPublishers.ofString(payload.toString())).build());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return;
            }
            if (response.statusCode() != 409 && response.statusCode() != 412) {
                throw new IllegalStateException("HAPI write returned HTTP " + response.statusCode());
            }
        }
        throw new IllegalStateException("HAPI write conflicted repeatedly");
    }

    static String hash(JsonNode resource) {
        return StorageSupport.sha256(resource.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String extension(JsonNode resource, String url, String field) {
        String value = null;
        for (JsonNode extension : resource.path("extension")) {
            if (url.equals(extension.path("url").asText())) {
                if (value != null || !extension.path(field).isTextual()) {
                    throw new IllegalStateException("HAPI resource has invalid source metadata");
                }

                value = extension.path(field).asText();
            }
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("HAPI resource has no source metadata");
        }
        return value;
    }

    JsonNode read(String relativePath) {
        if (!relativePath.matches("[A-Za-z0-9.$_?=&%/-]+")) {
            throw new IllegalArgumentException("Invalid HAPI relative path");
        }
        HttpResponse<String> response = send(request(base + "/" + relativePath).GET().build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HAPI read returned HTTP " + response.statusCode());
        }
        try {
            return json.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("HAPI returned invalid FHIR JSON", exception);
        }
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/fhir+json");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HAPI request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("HAPI request failed", exception);
        }
    }
}
