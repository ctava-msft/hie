package com.example.activator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebhookControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private EventPublisher publisher;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        publisher = mock(EventPublisher.class);
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(publisher, mapper))
                .setControllerAdvice(new ApiErrors())
                .defaultRequest(post("/").with(request -> {
                    request.setServletPath(request.getRequestURI());
                    return request;
                }))
                .addFilters(new ApiKeyFilter(TestProperties.create(Path.of("unused")))).build();
    }

    @Test
    void acknowledgesOnlyAfterEnqueue() throws Exception {
        mvc.perform(post("/api/webhooks/activator").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .header("Idempotency-Key", "event-123").contentType("application/json").content("{\"value\":39}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.eventId").value("event-123"));
        verify(publisher).publish(eq("event-123"), any(byte[].class));
    }

    @Test
    void enqueueFailureNeverAcknowledges() throws Exception {
        doThrow(new IllegalStateException("Broker unavailable")).when(publisher).publish(any(), any());
        mvc.perform(post("/api/webhooks/activator").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .header("Idempotency-Key", "event-123").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "null", "{broken", "{} {}", "\"hello\""})
    void rejectsMalformedOrNonObjectPayloads(String body) throws Exception {
        mvc.perform(post("/api/webhooks/activator").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .header("Idempotency-Key", "event-123").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsOversizedPayloadBeforeEnqueue() throws Exception {
        mvc.perform(post("/api/webhooks/activator").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .header("Idempotency-Key", "event-123").contentType("application/json")
                        .content("x".repeat(WebhookController.MAX_BODY_BYTES + 1)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(publisher);
    }

    @Test
    void requiresStableEventId() throws Exception {
        mvc.perform(post("/api/webhooks/activator").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", TestProperties.ADMIN_KEY})
    void rejectsWrongOrAdminKeyForWebhook(String key) throws Exception {
        mvc.perform(post("/api/webhooks/activator").header("X-API-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(publisher);
    }

    @Test
    void powerAutomatePreservesPayloadBytesAndKeyAcrossRetries() throws Exception {
        String payload = "{\n  \"synthetic\": true, \"temperatureC\": 39\n}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                            .contentType("application/json").content(action("event-123.action-1", payload)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.eventId").value("event-123.action-1"))
                    .andExpect(jsonPath("$.status").value("queued"));
        }
        verify(publisher, times(2)).publish(eq("event-123.action-1"), eq(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void powerAutomateBrokerFailureNeverAcknowledges() throws Exception {
        doThrow(new IllegalStateException("Broker unavailable")).when(publisher).publish(any(), any());
        mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .contentType("application/json").content(action("event-123", "{}")))
                .andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "[]", "null", "{broken", "{} {}",
            "{\"eventId\":\"event-123\"}",
            "{\"eventId\":123,\"payloadJson\":\"{}\"}",
            "{\"eventId\":\"event-123\",\"payloadJson\":{}}",
            "{\"eventId\":\"event-123\",\"payloadJson\":\"{}\",\"extra\":true}",
            "{\"eventId\":\"event-123\",\"payloadJson\":\"\"}",
            "{\"eventId\":\"event-123\",\"payloadJson\":\"[]\"}",
            "{\"eventId\":\"event-123\",\"payloadJson\":\"null\"}",
            "{\"eventId\":\"event-123\",\"payloadJson\":\"{broken\"}",
            "{\"eventId\":\"event-123\",\"payloadJson\":\"{} {}\"}"
    })
    void powerAutomateRejectsInvalidEnvelopesAndPayloads(String body) throws Exception {
        mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with spaces", "patient/event", "unsafe\u00e9"})
    void powerAutomateRequiresSafeSourceEventId(String eventId) throws Exception {
        mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .contentType("application/json").content(action(eventId, "{}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 129})
    void powerAutomateEnforcesExactKeyLength(int length) throws Exception {
        String eventId = "x".repeat(length);
        mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .contentType("application/json").content(action(eventId, "{}")))
                .andExpect(length == 128 ? status().isAccepted() : status().isBadRequest());
        if (length == 128) {
            verify(publisher).publish(eq(eventId), eq("{}".getBytes(StandardCharsets.UTF_8)));
        } else {
            verifyNoInteractions(publisher);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void powerAutomateEnforcesExactEnvelopeByteLimit(int excess) throws Exception {
        String emptyPayload = "{\"value\":\"\"}";
        int overhead = action("event-123", emptyPayload).getBytes(StandardCharsets.UTF_8).length;
        String payload = "{\"value\":\"" + "x".repeat(WebhookController.MAX_BODY_BYTES - overhead + excess) + "\"}";
        String body = action("event-123", payload);
        assertEquals(WebhookController.MAX_BODY_BYTES + excess, body.getBytes(StandardCharsets.UTF_8).length);
        mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .contentType("application/json").content(body))
                .andExpect(excess == 0 ? status().isAccepted() : status().isPayloadTooLarge());
        if (excess == 0) {
            verify(publisher).publish(eq("event-123"), eq(payload.getBytes(StandardCharsets.UTF_8)));
        } else {
            verifyNoInteractions(publisher);
        }
    }

    @Test
    void powerAutomateCountsUtf8BytesNotCharacters() throws Exception {
        String body = action("event-123", "{\"value\":\"" + "\u00e9".repeat(32768) + "\"}");
        assertTrue(body.length() < WebhookController.MAX_BODY_BYTES);
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length > WebhookController.MAX_BODY_BYTES);
        mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .contentType("application/json").content(body))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", TestProperties.ADMIN_KEY})
    void powerAutomateRejectsWrongOrAdminKey(String key) throws Exception {
        mvc.perform(post("/api/webhooks/power-automate").header("X-API-Key", key)
                        .contentType("application/json").content(action("event-123", "{}")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(publisher);
    }

    @Test
    void legacyWebhookStillAcceptsExactPayloadByteLimit() throws Exception {
        String emptyPayload = "{\"value\":\"\"}";
        String body = "{\"value\":\"" + "x".repeat(WebhookController.MAX_BODY_BYTES - emptyPayload.length()) + "\"}";
        assertEquals(WebhookController.MAX_BODY_BYTES, body.getBytes(StandardCharsets.UTF_8).length);
        mvc.perform(post("/api/webhooks/activator").header("X-API-Key", TestProperties.WEBHOOK_KEY)
                        .header("Idempotency-Key", "event-123").contentType("application/json").content(body))
                .andExpect(status().isAccepted());
        verify(publisher).publish(eq("event-123"), eq(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String action(String eventId, String payload) throws Exception {
        return mapper.writeValueAsString(mapper.createObjectNode()
                .put("eventId", eventId).put("payloadJson", payload));
    }
}
