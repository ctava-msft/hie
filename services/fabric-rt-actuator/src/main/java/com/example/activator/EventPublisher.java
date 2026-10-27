package com.example.activator;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.stereotype.Service;

@Service
public class EventPublisher {
    private final ServiceBusSenderClient sender;

    public EventPublisher(ServiceBusSenderClient sender) {
        this.sender = sender;
    }

    public void publish(String eventId, byte[] body) {
        var message = new ServiceBusMessage(BinaryData.fromBytes(body))
                .setMessageId(eventId).setCorrelationId(eventId).setContentType("application/json");
        message.getApplicationProperties().put("idempotencyKey", eventId);
        try {
            sender.sendMessage(message);
        } catch (ServiceBusException exception) {
            throw new IllegalStateException("Service Bus enqueue not confirmed", exception);
        }
    }
}
