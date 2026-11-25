package org.example.hie.medallion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.subscription-relay.enabled", havingValue = "true")
class SubscriptionRelay {
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRelay.class);
    private final QueuePublisher publisher;
    private final ObjectMapper json;
    private final byte[] authorization;
    private final String queue;
    private final int maxBytes;

    SubscriptionRelay(QueuePublisher publisher, ObjectMapper json,
                      @Value("${app.role}") String role,
                      @Value("${app.subscription-relay.token:}") String token,
                      @Value("${app.subscription-relay.queue:}") String queue,
                      @Value("${app.subscription-relay.max-bytes:131072}") int maxBytes) {
        if (!"subscription-relay".equals(role) || token == null || !token.matches("[A-Za-z0-9._~-]{32,256}")
                || queue == null || queue.isBlank() || maxBytes < 1 || maxBytes > 131072) {
            throw new IllegalStateException("Invalid subscription relay configuration");
        }
        this.publisher = publisher;
        this.json = json;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.queue = queue;
        this.maxBytes = maxBytes;
    }

    @PutMapping("/internal/fhir-subscription/{resourceType}/{resourceId}")
    ResponseEntity<Void> receive(@PathVariable String resourceType, @PathVariable String resourceId,
                                 HttpServletRequest request) throws IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String subscription = request.getHeader("X-Hie-Subscription");
        if (subscription == null || !subscription.matches("[A-Za-z0-9.-]{1,64}")) {
            return ResponseEntity.badRequest().build();
        }
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        JsonNode resource;
        try {
            resource = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException exception) {
            return ResponseEntity.badRequest().build();
        }
        if (resource == null || !resource.isObject()
                || !validType(resource.get("resourceType"))
                || !validId(resource.get("id"))
                || !validId(resource.path("meta").get("versionId"))
                || !resourceType.equals(resource.get("resourceType").textValue())
                || !resourceId.equals(resource.get("id").textValue())) {
            return ResponseEntity.badRequest().build();
        }
        String key = StorageSupport.sha256((subscription + "\n"
                + resource.get("resourceType").textValue() + "\n"
                + resource.get("id").textValue() + "\n"
                + resource.path("meta").get("versionId").textValue()).getBytes(StandardCharsets.UTF_8));
        String messageId = StorageSupport.sha256((key + "\n" + StorageSupport.sha256(body))
                .getBytes(StandardCharsets.UTF_8));
        try {
            publisher.publish(queue, messageId, key, resource);
        } catch (IllegalStateException exception) {
            LOG.error("FHIR subscription publication failed: {}", exception.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.noContent().build();
    }

    private static boolean validType(JsonNode node) {
        return node != null && node.isTextual() && node.textValue().matches("[A-Z][A-Za-z0-9]{0,63}");
    }

    private static boolean validId(JsonNode node) {
        return node != null && node.isTextual() && node.textValue().matches("[A-Za-z0-9.-]{1,64}");
    }
}
