package org.example.hie.medallion;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.example.hie.medallion.StageRecords.StagePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${app.role}' == 'silver' or '${app.role}' == 'gold'")
class QueueWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(QueueWorker.class);
    private final ServiceBusProcessorClient processor;
    private final SilverProcessor silver;
    private final GoldProcessor gold;
    private final ObjectMapper json;
    private final String role;
    private volatile boolean running;

    QueueWorker(ServiceBusClientBuilder builder, MedallionProperties properties,
                SilverProcessor silver, GoldProcessor gold, ObjectMapper json) {
        this.silver = silver;
        this.gold = gold;
        this.json = json;
        this.role = properties.role();
        String queue = role.equals("silver") ? properties.bronzeQueue() : properties.silverQueue();
        processor = builder.processor()
                .queueName(queue)
                .disableAutoComplete()
                .prefetchCount(0)
                .maxConcurrentCalls(properties.workerConcurrency())
                .maxAutoLockRenewDuration(Duration.ofMinutes(5))
                .processMessage(this::process)
                .processError(error -> LOG.error("Service Bus failure queue={} source={} error={}",
                        queue, error.getErrorSource(), error.getException().getClass().getSimpleName()))
                .buildProcessorClient();
    }

    void process(ServiceBusReceivedMessageContext context) {
        try {
            StagePointer pointer = json.readValue(context.getMessage().getBody().toBytes(), StagePointer.class);
            if (role.equals("silver")) {
                silver.process(pointer);
            } else {
                gold.process(pointer);
            }
            context.complete();
            LOG.info("Stage completed role={} eventId={} attempt={}",
                    role, pointer.eventId(), context.getMessage().getDeliveryCount());
        } catch (PermanentStageException exception) {
            context.deadLetter(new DeadLetterOptions()
                    .setDeadLetterReason(exception.reason())
                    .setDeadLetterErrorDescription("Stage validation failed"));
            LOG.warn("Stage message dead-lettered role={} reason={}", role, exception.reason());
        } catch (Exception exception) {
            LOG.error("Stage retry role={} error={}", role, exception.getClass().getSimpleName());
        }
    }

    @Override
    public void start() {
        processor.start();
        running = true;
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
