package com.example.activator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class DefinitionEditorTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private final DefinitionEditor editor = new DefinitionEditor(MAPPER);

    static JsonNode fixture(boolean stringInstance) throws Exception {
        JsonNode instance = MAPPER.readTree("""
                {"conditions":[{"field":"patientAge","value":18},
                {"field":"temperatureC","value":38.5}],"action":{"target":"keep-this"}}
                """);
        ObjectNode rule = MAPPER.createObjectNode().put("id", "stable-rule").put("type", "Trigger");
        ObjectNode definition = rule.putObject("payload").putObject("definition");
        if (stringInstance) {
            definition.put("instance", MAPPER.writeValueAsString(instance));
        } else {
            definition.set("instance", instance);
        }
        var entities = MAPPER.createArrayNode().add(rule)
                .add(MAPPER.createObjectNode().put("id", "source").put("type", "Source").put("preserve", true));
        ObjectNode result = MAPPER.createObjectNode();
        var parts = result.putObject("definition").putArray("parts");
        parts.addObject().put("path", "ReflexEntities.json").put("payloadType", "InlineBase64")
                .put("payload", Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(entities)));
        parts.addObject().put("path", ".platform").put("payloadType", "InlineBase64").put("payload", "e30=");
        return result;
    }

    static JsonNode entities(JsonNode definition) throws Exception {
        return MAPPER.readTree(Base64.getDecoder().decode(definition.at("/definition/parts/0/payload").asText()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesGraphOtherPartsAndInstanceRepresentation(boolean stringInstance) throws Exception {
        JsonNode original = fixture(stringInstance);
        String before = original.toString();
        JsonNode updated = editor.update(original, "stable-rule", new BigDecimal("39.0"));
        assertEquals(before, original.toString());
        assertEquals(original.at("/definition/parts/1"), updated.at("/definition/parts/1"));
        assertEquals(entities(original).get(1), entities(updated).get(1));
        JsonNode instance = entities(updated).get(0).at("/payload/definition/instance");
        assertEquals(stringInstance, instance.isTextual());
        JsonNode rule = stringInstance ? MAPPER.readTree(instance.asText()) : instance;
        assertEquals(39.0, rule.at("/conditions/1/value").asDouble());
        assertEquals(18, rule.at("/conditions/0/value").asInt());
        assertEquals("keep-this", rule.at("/action/target").asText());
        assertNotEquals(editor.checksum(original), editor.checksum(updated));
        assertEquals(editor.checksum(updated), editor.checksum(editor.update(updated, "stable-rule", new BigDecimal("39.0"))));
    }

    @Test
    void refusesUnknownEntity() throws Exception {
        JsonNode original = fixture(false);
        assertThrows(IllegalArgumentException.class, () -> editor.update(original, "unknown", BigDecimal.valueOf(39)));
    }

    @Test
    void refusesAmbiguousThresholdInsteadOfChangingFirstMatch() throws Exception {
        JsonNode original = fixture(false);
        var entities = entities(original);
        ((ObjectNode) entities.get(0).at("/payload/definition/instance/conditions/0")).put("field", "temperatureC");
        ((ObjectNode) original.at("/definition/parts/0")).put("payload",
                Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(entities)));
        var exception = assertThrows(IllegalArgumentException.class,
                () -> editor.update(original, "stable-rule", BigDecimal.valueOf(39)));
        assertTrue(exception.getMessage().contains("exactly one"));
    }
}
