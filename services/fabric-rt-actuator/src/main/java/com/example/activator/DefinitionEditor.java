package com.example.activator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DefinitionEditor {
    private final ObjectMapper mapper;

    public DefinitionEditor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode update(JsonNode original, String entityId, BigDecimal threshold) {
        if (original == null || !original.isObject()) {
            throw new IllegalArgumentException("Expected a definition object");
        }
        ObjectNode copy = original.deepCopy();
        JsonNode parts = copy.path("definition").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalArgumentException("Definition parts must be a non-empty array");
        }
        List<JsonNode> matches = new ArrayList<>();
        parts.forEach(part -> {
            if ("ReflexEntities.json".equals(part.path("path").asText())) {
                matches.add(part);
            }
        });
        if (matches.size() != 1 || !(matches.getFirst() instanceof ObjectNode part)
                || !"InlineBase64".equals(part.path("payloadType").asText())) {
            throw new IllegalArgumentException("Expected exactly one InlineBase64 ReflexEntities.json part");
        }
        try {
            JsonNode entities = mapper.readTree(Base64.getDecoder().decode(part.path("payload").asText()));
            if (entities == null || !entities.isArray()) {
                throw new IllegalArgumentException("Expected a UI-generated entity array");
            }
            List<JsonNode> targets = new ArrayList<>();
            entities.forEach(entity -> {
                if (entityId.equals(entity.path("id").asText())) {
                    targets.add(entity);
                }
            });
            if (targets.size() != 1) {
                throw new IllegalArgumentException("Entity ID must identify exactly one existing entity");
            }
            JsonNode target = targets.getFirst();
            String type = target.path("type").asText().toLowerCase(Locale.ROOT);
            if (!type.contains("trigger") && !type.contains("rule")) {
                throw new IllegalArgumentException("Entity is not a rule or trigger");
            }
            JsonNode container = target.path("payload").path("definition");
            if (!(container instanceof ObjectNode definition)) {
                throw new IllegalArgumentException("Unknown rule definition shape");
            }
            JsonNode instance = definition.path("instance");
            boolean stringInstance = instance.isTextual();
            JsonNode rule = stringInstance ? mapper.readTree(instance.textValue()) : instance;
            List<ValueSlot> slots = new ArrayList<>();
            findTemperature(rule, slots);
            if (slots.size() != 1 || !slots.getFirst().node().path(slots.getFirst().key()).isNumber()) {
                throw new IllegalArgumentException("Expected exactly one numeric temperatureC condition");
            }
            ValueSlot slot = slots.getFirst();
            slot.node().put(slot.key(), threshold);
            if (stringInstance) {
                definition.put("instance", mapper.writeValueAsString(rule));
            }
            part.put("payload", Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(entities)));
            return mapper.createObjectNode().set("definition", copy.get("definition"));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid definition JSON", exception);
        }
    }

    private void findTemperature(JsonNode node, List<ValueSlot> slots) {
        if (node == null) {
            return;
        }
        if (node instanceof ObjectNode object) {
            boolean temperature = List.of("field", "property", "column").stream()
                    .anyMatch(key -> "temperatureC".equals(object.path(key).asText()));
            if (temperature) {
                for (String key : List.of("value", "threshold", "rightValue")) {
                    if (object.has(key)) {
                        slots.add(new ValueSlot(object, key));
                    }
                }
            }
            object.elements().forEachRemaining(child -> findTemperature(child, slots));
        } else if (node.isArray()) {
            node.forEach(child -> findTemperature(child, slots));
        }
    }

    public String checksum(JsonNode definition) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(definition.path("definition"))));
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("Could not checksum definition", exception);
        }
    }

    private record ValueSlot(ObjectNode node, String key) {}
}
