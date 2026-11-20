package com.example.activator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {
    static final int MAX_BODY_BYTES = 64 * 1024;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;

    public WebhookController(EventPublisher publisher, ObjectMapper mapper) {
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/webhooks/activator", consumes = "application/json")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader("Idempotency-Key") String eventId, HttpServletRequest request) throws IOException {
        validateEventId(eventId);
        byte[] body = readBody(request);
        parseObject(body);
        return enqueue(eventId, body);
    }

    @PostMapping(value = "/api/webhooks/power-automate", consumes = "application/json")
    public ResponseEntity<Map<String, String>> receivePowerAutomate(HttpServletRequest request) throws IOException {
        JsonNode action = parseObject(readBody(request));
        if (action.size() != 2 || !action.path("eventId").isTextual() || !action.path("payloadJson").isTextual()) {
            throw new IllegalArgumentException("Expected eventId and payloadJson strings");
        }
        String eventId = action.path("eventId").textValue();
        validateEventId(eventId);
        byte[] body = action.path("payloadJson").textValue().getBytes(StandardCharsets.UTF_8);
        parseObject(body);
        return enqueue(eventId, body);
    }

    private static void validateEventId(String eventId) {
        if (!eventId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Event key must be 1-128 safe ASCII characters");
        }
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiErrors.PayloadTooLarge();
        }
        return body;
    }

    private JsonNode parseObject(byte[] body) throws IOException {
        try {
            JsonNode payload = mapper.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("Expected a JSON object");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed JSON", exception);
        }
    }

    private ResponseEntity<Map<String, String>> enqueue(String eventId, byte[] body) {
        publisher.publish(eventId, body);
        return ResponseEntity.accepted().body(Map.of("eventId", eventId, "status", "queued"));
    }
}
