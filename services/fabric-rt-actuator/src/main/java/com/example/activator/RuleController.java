package com.example.activator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rules")
public class RuleController {
    private static final Logger LOG = LoggerFactory.getLogger(RuleController.class);
    private final FabricClient fabric;
    private final DefinitionEditor editor;
    private final DeploymentStore store;
    private final ObjectMapper mapper;
    private final AppProperties properties;

    public RuleController(FabricClient fabric, DefinitionEditor editor, DeploymentStore store,
                          ObjectMapper mapper, AppProperties properties) {
        this.fabric = fabric;
        this.editor = editor;
        this.store = store;
        this.mapper = mapper;
        this.properties = properties;
    }

    @GetMapping("/definition")
    public JsonNode definition() throws IOException {
        JsonNode current = fabric.getDefinition();
        return mapper.createObjectNode().put("checksum", editor.checksum(current)).set("definition", current.get("definition"));
    }

    @PostMapping("/temperature")
    public JsonNode update(@Valid @RequestBody TemperatureRequest request) throws IOException {
        return store.exclusively(() -> {
            JsonNode current = fabric.getDefinition();
            checkChecksum(current, request.expectedChecksum());
            JsonNode desired = editor.update(current, request.entityId(), request.temperatureC());
            return apply(current, desired, request.ruleId(), request.version(), request.dryRun(), null);
        });
    }

    @PostMapping("/rollback")
    public JsonNode rollback(@Valid @RequestBody RollbackRequest request) throws IOException {
        return store.exclusively(() -> {
            JsonNode current = fabric.getDefinition();
            checkChecksum(current, request.expectedChecksum());
            JsonNode previous = store.previous(request.deploymentId());
            return apply(current, previous, "rollback", 0, request.dryRun(), request.deploymentId());
        });
    }

    private void checkChecksum(JsonNode current, String expected) {
        if (!editor.checksum(current).equals(expected)) {
            throw new ApiErrors.Conflict("Definition changed; retrieve and review the current definition");
        }
    }

    private JsonNode apply(JsonNode current, JsonNode desired, String ruleId, int version,
                           boolean dryRun, UUID rollbackOf) throws IOException {
        String checksum = editor.checksum(desired);
        UUID id = UUID.randomUUID();
        ObjectNode record = mapper.createObjectNode()
                .put("deploymentId", id.toString()).put("ruleId", ruleId).put("version", version)
                .put("workspaceId", properties.fabricWorkspaceId()).put("activatorId", properties.fabricActivatorId())
                .put("timestamp", Instant.now().toString()).put("checksum", checksum)
                .put("previousChecksum", editor.checksum(current));
        if (rollbackOf != null) {
            record.put("rollbackOf", rollbackOf.toString());
        }
        if (dryRun) {
            return record.put("result", "DRY_RUN");
        }
        if (checksum.equals(editor.checksum(current))) {
            store.save(id, "-record", record.put("result", "UNCHANGED"));
            return record;
        }
        store.save(id, "-before", current);
        record.put("rollbackReference", id.toString());
        store.save(id, "-desired", desired);
        store.save(id, "-record", record.put("result", "SUBMITTED"));
        try {
            fabric.updateDefinition(desired);
            JsonNode observed = fabric.getDefinition();
            if (!checksum.equals(editor.checksum(observed))) {
                throw new IOException("Post-deployment definition verification failed");
            }
            store.save(id, "-record", record.put("result", "VERIFIED"));
        } catch (IOException | RuntimeException exception) {
            // A timeout can occur after Fabric commits. Never blindly overwrite with an automatic rollback.
            try {
                store.save(id, "-record", record.put("result", "UNKNOWN_OR_FAILED"));
            } catch (IOException auditFailure) {
                exception.addSuppressed(auditFailure);
                LOG.error("Deployment outcome could not be persisted deploymentId={}", id);
            }
            LOG.error("Rule deployment unconfirmed deploymentId={} type={}", id, exception.getClass().getSimpleName());
            throw new ApiErrors.UnconfirmedDeployment(id, exception);
        }
        LOG.info("Rule deployment verified deploymentId={} version={} checksum={}", id, version, checksum);
        return record;
    }

    public record TemperatureRequest(
            @NotBlank @Size(max = 128) String ruleId,
            @NotBlank @Size(max = 128) String entityId,
            @Positive int version,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal temperatureC,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedChecksum,
            @NotNull Boolean dryRun) {}

    public record RollbackRequest(
            @NotNull UUID deploymentId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedChecksum,
            @NotNull Boolean dryRun) {}
}
