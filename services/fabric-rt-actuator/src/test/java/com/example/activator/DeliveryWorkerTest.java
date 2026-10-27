package com.example.activator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class DeliveryWorkerTest {
    @Mock HttpClient http;
    @Mock HttpResponse<Void> response;
    @Mock ServiceBusReceivedMessageContext context;
    private DeliveryWorker worker;

    @BeforeEach
    void setup() {
        var message = mock(ServiceBusReceivedMessage.class);
        when(context.getMessage()).thenReturn(message);
        when(message.getMessageId()).thenReturn("stable-event");
        when(message.getBody()).thenReturn(BinaryData.fromString("{\"synthetic\":true}"));
        worker = new DeliveryWorker(mock(ServiceBusClientBuilder.class, RETURNS_DEEP_STUBS),
                TestProperties.create(Path.of("unused")), http);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 204})
    void completesOnlyOnSuccessfulDownstreamResponse(int status) throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        worker.deliver(context);
        verify(context).complete();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        assertEquals("stable-event", request.getValue().headers().firstValue("Idempotency-Key").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void leavesRetryableFailuresUnsettledForLockExpiry(int status) throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        worker.deliver(context);
        verify(context, never()).complete();
        verify(context, never()).deadLetter(any(DeadLetterOptions.class));
        verify(context, never()).abandon();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 401, 403, 404, 422})
    void deadLettersPermanentFailures(int status) throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        worker.deliver(context);
        verify(context).deadLetter(any(DeadLetterOptions.class));
        verify(context, never()).complete();
    }

    @Test
    void networkFailureDoesNotAcknowledge() throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenThrow(new IOException("Timeout"));
        worker.deliver(context);
        verify(context, never()).complete();
        verify(context, never()).deadLetter(any(DeadLetterOptions.class));
    }

    @Test
    void replayPreservesOriginalDownstreamIdempotencyKey() throws Exception {
        when(context.getMessage().getApplicationProperties()).thenReturn(Map.of("idempotencyKey", "original-event"));
        when(response.statusCode()).thenReturn(204);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        worker.deliver(context);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        assertEquals("original-event", request.getValue().headers().firstValue("Idempotency-Key").orElseThrow());
        verify(context).complete();
    }
}
