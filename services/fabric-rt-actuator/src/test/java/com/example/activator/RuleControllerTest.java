package com.example.activator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleControllerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DefinitionEditor editor = new DefinitionEditor(mapper);
    private FabricClient fabric;
    private RuleController controller;
    private JsonNode original;

    @BeforeEach
    void setup() throws Exception {
        fabric = mock(FabricClient.class);
        var properties = TestProperties.create(directory);
        controller = new RuleController(fabric, editor, new DeploymentStore(properties, mapper), mapper, properties);
        original = DefinitionEditorTest.fixture(false);
    }

    private RuleController.TemperatureRequest request(boolean dryRun) {
        return new RuleController.TemperatureRequest("adult-temperature", "stable-rule", 3,
                new BigDecimal("39.0"), editor.checksum(original), dryRun);
    }

    @Test
    void dryRunNeverDeploysOrCreatesSnapshot() throws Exception {
        when(fabric.getDefinition()).thenReturn(original);
        JsonNode result = controller.update(request(true));
        assertEquals("DRY_RUN", result.path("result").asText());
        assertFalse(result.has("rollbackReference"));
        verify(fabric, never()).updateDefinition(any());
        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.toString().endsWith(".json")).count());
        }
    }

    @Test
    void staleChecksumPreventsDeployment() throws Exception {
        when(fabric.getDefinition()).thenReturn(editor.update(original, "stable-rule", BigDecimal.valueOf(40)));
        assertThrows(ApiErrors.Conflict.class, () -> controller.update(request(false)));
        verify(fabric, never()).updateDefinition(any());
    }

    @Test
    void persistsPriorDefinitionAndAuditAndSupportsRollback() throws Exception {
        JsonNode desired = editor.update(original, "stable-rule", new BigDecimal("39.0"));
        when(fabric.getDefinition()).thenReturn(original, desired);
        JsonNode result = controller.update(request(false));
        assertEquals("VERIFIED", result.path("result").asText());
        UUID id = UUID.fromString(result.path("rollbackReference").asText());
        assertEquals(original, mapper.readTree(Files.readAllBytes(directory.resolve(id + "-before.json"))));
        assertEquals(3, result.path("version").asInt());
        verify(fabric).updateDefinition(desired);

        when(fabric.getDefinition()).thenReturn(desired, original);
        JsonNode rollback = controller.rollback(new RuleController.RollbackRequest(id, editor.checksum(desired), false));
        assertEquals("VERIFIED", rollback.path("result").asText());
        verify(fabric).updateDefinition(original);
    }

    @Test
    void unconfirmedUpdateRetainsSnapshotAndExplicitOutcome() throws Exception {
        when(fabric.getDefinition()).thenReturn(original);
        org.mockito.Mockito.doThrow(new IOException("Timeout")).when(fabric).updateDefinition(any());
        assertThrows(ApiErrors.UnconfirmedDeployment.class, () -> controller.update(request(false)));
        try (var files = Files.list(directory)) {
            Path audit = files.filter(path -> path.toString().endsWith("-record.json")).findFirst().orElseThrow();
            JsonNode record = mapper.readTree(Files.readAllBytes(audit));
            assertEquals("UNKNOWN_OR_FAILED", record.path("result").asText());
            UUID id = UUID.fromString(record.path("rollbackReference").asText());
            assertEquals(original, mapper.readTree(Files.readAllBytes(directory.resolve(id + "-before.json"))));
        }
    }
}
