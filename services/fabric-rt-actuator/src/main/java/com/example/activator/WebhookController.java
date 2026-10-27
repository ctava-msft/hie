package com.example.activator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
        if (!eventId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-128 safe ASCII characters");
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(413).body(Map.of("error", "Maximum payload size is 65536 bytes"));
        }
        try {
            var payload = mapper.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("Expected a JSON object");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Malformed JSON", exception);
        }
        publisher.publish(eventId, body);
        return ResponseEntity.accepted().body(Map.of("eventId", eventId, "status", "queued"));
    }
}
