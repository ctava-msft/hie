package org.example.hie.medallion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SubscriptionRelayTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String BODY = """
            {"resourceType":"Patient","id":"p1","meta":{"versionId":"7"}}
            """;
    private final QueuePublisher publisher = Mockito.mock(QueuePublisher.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new SubscriptionRelay(publisher, new ObjectMapper(), "subscription-relay", TOKEN, "events", 1024)).build();

    @Test
    void requiresStrongConfiguration() {
        assertThrows(IllegalStateException.class, () ->
                new SubscriptionRelay(publisher, new ObjectMapper(), "subscription-relay", "short", "events", 1024));
        assertThrows(IllegalStateException.class, () ->
                new SubscriptionRelay(publisher, new ObjectMapper(), "silver", TOKEN, "events", 1024));
    }

    @Test
    void rejectsUnauthorizedAndUnversionedNotifications() throws Exception {
        mvc.perform(put("/internal/fhir-subscription/Patient/p1").content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/fhir-subscription/Patient/p1").header("Authorization", "Bearer " + TOKEN)
                .header("X-Hie-Subscription", "sub1").content("{\"resourceType\":\"Patient\",\"id\":\"p1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/internal/fhir-subscription/Patient/p1").header("Authorization", "Bearer " + TOKEN)
                .header("X-Hie-Subscription", "sub1").content(BODY + "{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/internal/fhir-subscription/Patient/p2").header("Authorization", "Bearer " + TOKEN)
                .header("X-Hie-Subscription", "sub1").content(BODY))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void acknowledgesOnlyAfterConfirmedPublication() throws Exception {
        doThrow(new IllegalStateException("failure")).when(publisher)
                .publish(anyString(), anyString(), anyString(), any());
        mvc.perform(put("/internal/fhir-subscription/Patient/p1").header("Authorization", "Bearer " + TOKEN)
                .header("X-Hie-Subscription", "sub1").content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void sendsStableIdentityAndDistinguishesConflictingPayloads() throws Exception {
        for (String body : new String[] { BODY, BODY, BODY.replace("\"p1\"", "\"p1\",\"active\":true") }) {
            mvc.perform(put("/internal/fhir-subscription/Patient/p1").header("Authorization", "Bearer " + TOKEN)
                    .header("X-Hie-Subscription", "sub1").content(body))
                    .andExpect(status().isNoContent());
        }
        ArgumentCaptor<String> messageIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(publisher, Mockito.times(3)).publish(Mockito.eq("events"), messageIds.capture(),
                keys.capture(), any());
        assertEquals(messageIds.getAllValues().get(0), messageIds.getAllValues().get(1));
        assertNotEquals(messageIds.getAllValues().get(0), messageIds.getAllValues().get(2));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(2));
    }

    @Test
    void rejectsOversizeBody() throws Exception {
        mvc.perform(put("/internal/fhir-subscription/Patient/p1").header("Authorization", "Bearer " + TOKEN)
                .header("X-Hie-Subscription", "sub1").content("x".repeat(1025)))
                .andExpect(status().isPayloadTooLarge());
        verify(publisher, never()).publish(anyString(), anyString(), anyString(), any());
    }
}
