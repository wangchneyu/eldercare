package com.eldercare.iot.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies committed C04/C05 contract assets without treating a mock broker as broker evidence. */
class MessageContractAssetsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void c04GoldenMessageMatchesFrozenSchemaAndRoutingMetadata() throws Exception {
        JsonNode schema = resource("contracts/c04-elder-vital-raw.schema.json");
        JsonNode fixture = resource("contracts/c04-elder-vital-raw.golden.json");

        assertMatches(schema, fixture, "$" );
        assertEquals("elder-vital-raw", schema.path("x-rocketmq").path("topic").asText());
        assertEquals("deviceType", schema.path("x-rocketmq").path("tag").asText());
        assertTrue(fixture.path("payload").path("elder_id").isTextual());
    }

    @Test
    void c05GoldenMessageMatchesFrozenSchemaAndRoutingMetadata() throws Exception {
        JsonNode schema = resource("contracts/c05-elder-sos-event.schema.json");
        JsonNode fixture = resource("contracts/c05-elder-sos-event.golden.json");

        assertMatches(schema, fixture, "$" );
        assertEquals("elder-sos-event", schema.path("x-rocketmq").path("topic").asText());
        assertEquals("SOS", schema.path("x-rocketmq").path("tagByEventType").path("SOS_TRIGGERED").asText());
        assertEquals("FALL", schema.path("x-rocketmq").path("tagByEventType").path("FALL_DETECTED").asText());
        assertTrue(fixture.path("payload").path("elderId").isNull());
    }

    private JsonNode resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing contract asset: " + path);
            return objectMapper.readTree(stream);
        }
    }

    private void assertMatches(JsonNode schema, JsonNode value, String path) {
        assertType(schema.path("type"), value, path);

        if (schema.has("const")) {
            assertEquals(schema.get("const"), value, path + " must match const");
        }
        if (schema.has("enum")) {
            boolean matched = false;
            for (JsonNode candidate : schema.path("enum")) {
                if (candidate.equals(value)) {
                    matched = true;
                    break;
                }
            }
            assertTrue(matched, path + " must be one of the declared enum values");
        }
        if (schema.has("pattern") && value.isTextual()) {
            assertTrue(Pattern.compile(schema.path("pattern").asText()).matcher(value.asText()).matches(),
                    path + " must match its pattern");
        }
        if ("date-time".equals(schema.path("format").asText()) && value.isTextual()) {
            assertDoesNotThrow(() -> OffsetDateTime.parse(value.asText()), path + " must be an ISO-8601 timestamp");
        }

        if (!value.isObject()) {
            return;
        }

        JsonNode required = schema.path("required");
        for (JsonNode field : required) {
            assertTrue(value.has(field.asText()), path + " is missing required field " + field.asText());
        }

        JsonNode properties = schema.path("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode fieldSchema = properties.get(field.getKey());
            if (fieldSchema == null) {
                assertFalse(schema.path("additionalProperties").isBoolean()
                                && !schema.path("additionalProperties").asBoolean(),
                        path + " contains undeclared field " + field.getKey());
                continue;
            }
            assertMatches(fieldSchema, field.getValue(), path + "." + field.getKey());
        }
    }

    private void assertType(JsonNode declaredType, JsonNode value, String path) {
        if (declaredType.isMissingNode()) {
            return;
        }
        if (declaredType.isTextual()) {
            assertTrue(matchesType(declaredType.asText(), value), path + " has an unexpected JSON type");
            return;
        }
        assertTrue(declaredType.isArray(), path + " type must be a string or array");
        for (JsonNode type : declaredType) {
            if (matchesType(type.asText(), value)) {
                return;
            }
        }
        fail(path + " has an unexpected JSON type");
    }

    private boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}
