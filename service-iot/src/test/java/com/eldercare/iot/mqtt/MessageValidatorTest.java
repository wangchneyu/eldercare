package com.eldercare.iot.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MessageValidatorTest {

    private final MessageValidator validator = new MessageValidator();

    private static final String VALID_VITAL = """
        {
            "messageId": "msg-001",
            "deviceId": "DEV-001",
            "messageType": "VITAL_SIGN",
            "protocolVersion": "1.0",
            "occurredAt": "2026-07-24T02:30:00Z",
            "sequenceNo": 1,
            "payload": { "heart_rate": 75 }
        }
        """;

    private static final String VALID_SOS = """
        {
            "messageId": "msg-002",
            "deviceId": "DEV-SOS-01",
            "messageType": "SOS",
            "protocolVersion": "1.0",
            "occurredAt": "2026-07-24T02:30:00Z",
            "sequenceNo": 2,
            "payload": { "triggerType": "BUTTON_PRESS" }
        }
        """;

    private static final String VALID_HEARTBEAT = """
        {
            "messageId": "msg-003",
            "deviceId": "DEV-001",
            "messageType": "HEARTBEAT",
            "protocolVersion": "1.0",
            "occurredAt": "2026-07-24T02:30:00Z",
            "sequenceNo": 3,
            "payload": {}
        }
        """;

    @Test
    void valid_vital_sign() {
        Optional<JsonNode> result = validator.validate(VALID_VITAL.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isPresent());
        assertEquals("VITAL_SIGN", result.get().get("messageType").asText());
    }

    @Test
    void valid_sos() {
        Optional<JsonNode> result = validator.validate(VALID_SOS.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isPresent());
        assertEquals("SOS", result.get().get("messageType").asText());
    }

    @Test
    void valid_heartbeat() {
        Optional<JsonNode> result = validator.validate(VALID_HEARTBEAT.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isPresent());
    }

    @Test
    void null_payload() {
        assertTrue(validator.validate(null).isEmpty());
    }

    @Test
    void empty_payload() {
        assertTrue(validator.validate(new byte[0]).isEmpty());
    }

    @Test
    void invalid_json() {
        assertTrue(validator.validate("not json".getBytes()).isEmpty());
    }

    @Test
    void missing_messageId() {
        String json = """
            { "deviceId": "D1", "messageType": "HEARTBEAT", "protocolVersion": "1.0", "occurredAt": "2026-07-24T00:00:00Z" }
            """;
        assertTrue(validator.validate(json.getBytes()).isEmpty());
    }

    @Test
    void missing_messageType() {
        String json = """
            { "messageId": "m1", "deviceId": "D1", "protocolVersion": "1.0", "occurredAt": "2026-07-24T00:00:00Z" }
            """;
        assertTrue(validator.validate(json.getBytes()).isEmpty());
    }

    @Test
    void unknown_messageType() {
        String json = """
            { "messageId": "m1", "deviceId": "D1", "messageType": "UNKNOWN_TYPE", "protocolVersion": "1.0", "occurredAt": "2026-07-24T00:00:00Z" }
            """;
        assertTrue(validator.validate(json.getBytes()).isEmpty());
    }

    @Test
    void oversized_payload() {
        byte[] big = new byte[70000]; // > 64KB
        assertTrue(validator.validate(big).isEmpty());
    }
}
