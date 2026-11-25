package org.example.hie.medallion;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
class QueuePublisher {
    private final ServiceBusClientBuilder builder;
    private final ObjectMapper json;

    QueuePublisher(ServiceBusClientBuilder builder, ObjectMapper json) {
        this.builder = builder;
        this.json = json;
    }

    void publish(String queueName, String eventId, Object payload) {
        publish(queueName, eventId, eventId, payload);
    }

    void publish(String queueName, String messageId, String idempotencyKey, Object payload) {
        try (var sender = builder.sender().queueName(queueName).buildClient()) {
            var body = BinaryData.fromBytes(json.writeValueAsBytes(payload));
            var message = new ServiceBusMessage(body)
                    .setMessageId(messageId)
                    .setCorrelationId(idempotencyKey)
                    .setContentType("application/json");
            message.getApplicationProperties().put("idempotencyKey", idempotencyKey);
            sender.sendMessage(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Queue publication not confirmed", exception);
        }
    }
}
