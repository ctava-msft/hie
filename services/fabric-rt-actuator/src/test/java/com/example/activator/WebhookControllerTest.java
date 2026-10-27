package com.example.activator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebhookControllerTest {
    private EventPublisher publisher;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        publisher = mock(EventPublisher.class);
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(publisher, new ObjectMapper()))
                .setControllerAdvice(new ApiErrors())
                .defaultRequest(post("/api/webhooks/activator").servletPath("/api/webhooks/activator"))
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
}
