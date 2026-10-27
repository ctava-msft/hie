package com.example.activator;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class DeliveryWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryWorker.class);
    private final ServiceBusProcessorClient processor;
    private final AppProperties properties;
    private final HttpClient http;
    private volatile boolean running;

    public DeliveryWorker(ServiceBusClientBuilder builder, AppProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
        this.processor = builder.processor().queueName(properties.serviceBusQueue())
                .disableAutoComplete().prefetchCount(0).maxConcurrentCalls(properties.workerConcurrency())
                .maxAutoLockRenewDuration(Duration.ofMinutes(2))
                .processMessage(this::deliver)
                .processError(error -> LOG.error("Service Bus receiver failure source={} type={}",
                        error.getErrorSource(), error.getException().getClass().getSimpleName()))
                .buildProcessorClient();
    }

    void deliver(ServiceBusReceivedMessageContext context) {
        var message = context.getMessage();
        Object keyProperty = message.getApplicationProperties().get("idempotencyKey");
        String deliveryKey = keyProperty instanceof String key ? key : message.getMessageId();
        if (deliveryKey == null || !deliveryKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            context.deadLetter(new DeadLetterOptions().setDeadLetterReason("InvalidDeliveryKey"));
            LOG.warn("Delivery dead-lettered reason=InvalidDeliveryKey");
            return;
        }
        try {
            var request = HttpRequest.newBuilder(properties.downstreamUrl())
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", deliveryKey)
                    .header("X-Correlation-ID", message.getMessageId());
            if (properties.downstreamBearerToken() != null && !properties.downstreamBearerToken().isBlank()) {
                request.header("Authorization", "Bearer " + properties.downstreamBearerToken());
            }
            var response = http.send(request.POST(
                    HttpRequest.BodyPublishers.ofByteArray(message.getBody().toBytes())).build(),
                    HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                context.complete();
                LOG.info("Delivery completed eventId={} attempt={}", message.getMessageId(), message.getDeliveryCount());
            } else if (status == 408 || status == 429 || status >= 500) {
                retry(context, "http-" + status);
            } else {
                context.deadLetter(new DeadLetterOptions().setDeadLetterReason("DownstreamRejected")
                        .setDeadLetterErrorDescription("HTTP " + status));
                LOG.warn("Delivery dead-lettered eventId={} status={}", message.getMessageId(), status);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            retry(context, "interrupted");
        } catch (IOException exception) {
            retry(context, exception.getClass().getSimpleName());
        }
        // Settlement errors propagate to the SDK; an unsettled lock is never treated as delivery success.
    }

    private void retry(ServiceBusReceivedMessageContext context, String reason) {
        LOG.warn("Delivery retry eventId={} attempt={} reason={}",
                context.getMessage().getMessageId(), context.getMessage().getDeliveryCount(), reason);
        // Leave the message locked instead of abandoning into a tight retry loop.
        // The queue's lock duration controls the next retry, and MaxDeliveryCount bounds attempts.
    }

    @Override
    public void start() {
        if (properties.workerEnabled()) {
            processor.start();
            running = true;
        }
    }

    @Override
    public void stop() {
        processor.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
