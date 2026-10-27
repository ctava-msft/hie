package com.example.activator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class FabricClientTest {
    @Mock HttpClient http;
    @Mock TokenCredential credential;
    private FabricClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OPERATION = "https://api.fabric.microsoft.com/v1/operations/33333333-3333-3333-3333-333333333333";

    @BeforeEach
    void setup() {
        client = new FabricClient(http, credential, mapper, TestProperties.create(Path.of("unused")));
    }

    private void token() {
        when(credential.getToken(any())).thenReturn(Mono.just(
                new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int code, String body, Map<String, List<String>> headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        org.mockito.Mockito.lenient().when(response.body()).thenReturn(body);
        org.mockito.Mockito.lenient().when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }

    @Test
    void synchronousGetUsesDocumentedReflexEndpoint() throws Exception {
        token();
        var success = response(200, "{\"definition\":{\"parts\":[]}}", Map.of());
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(success);
        assertEquals(0, client.getDefinition().at("/definition/parts").size());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(request.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("POST", request.getValue().method());
        assertEquals("/v1/workspaces/11111111-1111-1111-1111-111111111111/reflexes/22222222-2222-2222-2222-222222222222/getDefinition",
                request.getValue().uri().getPath());
    }

    @Test
    void asynchronousGetPollsAndRetrievesResult() throws Exception {
        token();
        var accepted = response(202, "", Map.of("Location", List.of(OPERATION), "Retry-After", List.of("0")));
        var succeeded = response(200, "{\"status\":\"Succeeded\"}", Map.of());
        var result = response(200, "{\"definition\":{\"parts\":[]}}", Map.of());
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(accepted, succeeded, result);
        assertEquals(0, client.getDefinition().at("/definition/parts").size());
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(3)).send(requests.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals(OPERATION + "/result", requests.getAllValues().get(2).uri().toString());
    }

    @Test
    void updateWaitsForSuccessWithoutFetchingResult() throws Exception {
        token();
        var accepted = response(202, "", Map.of("Location", List.of(OPERATION), "Retry-After", List.of("0")));
        var succeeded = response(200, "{\"status\":\"Succeeded\"}", Map.of());
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(accepted, succeeded);
        client.updateDefinition(mapper.createObjectNode());
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(2)).send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void neverSendsBearerTokenToUntrustedOperationHost() throws Exception {
        token();
        var accepted = response(202, "", Map.of("Location", List.of("https://untrusted.example/steal")));
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(accepted);
        assertThrows(IOException.class, () -> client.getDefinition());
        org.mockito.Mockito.verify(http).send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }
}
