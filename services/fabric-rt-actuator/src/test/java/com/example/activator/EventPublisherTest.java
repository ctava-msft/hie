package com.example.activator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EventPublisherTest {
    @Test
    void publishesUnmodifiedBodyWithStableBrokerAndDeliveryKeys() {
        var sender = mock(ServiceBusSenderClient.class);
        byte[] body = "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8);
        new EventPublisher(sender).publish("event-001", body);
        var captor = ArgumentCaptor.forClass(ServiceBusMessage.class);
        verify(sender).sendMessage(captor.capture());
        ServiceBusMessage message = captor.getValue();
        assertEquals("event-001", message.getMessageId());
        assertEquals("event-001", message.getCorrelationId());
        assertEquals("event-001", message.getApplicationProperties().get("idempotencyKey"));
        assertEquals("application/json", message.getContentType());
        assertArrayEquals(body, message.getBody().toBytes());
    }
}
