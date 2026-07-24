package com.eldercare.iot.parser;

import com.eldercare.iot.parser.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorParserTest {

    private final SimulatorParser parser = new SimulatorParser();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parser_code() {
        assertEquals("simulator", parser.parserCode());
    }

    @Test
    void parse_vital_sign() throws Exception {
        String json = """
            {
                "messageId": "msg-001",
                "deviceId": "DEV-001",
                "messageType": "VITAL_SIGN",
                "protocolVersion": "1.0",
                "occurredAt": "2026-07-24T02:30:00Z",
                "sequenceNo": 1,
                "payload": {
                    "heart_rate": 75,
                    "respiratory_rate": 16,
                    "body_movement": 1,
                    "bed_status": "IN_BED"
                }
            }
            """;
        JsonNode envelope = mapper.readTree(json);
        RawDeviceMessage raw = new RawDeviceMessage(
            "elder/P001/MATTRESS/DEV-001/up/telemetry",
            envelope, "P001", "MATTRESS", "DEV-001", "VITAL_SIGN",
            "EVT-001", "trace-001", OffsetDateTime.parse("2026-07-24T02:30:00Z")
        );

        Optional<ParsedEvent> result = parser.parse(raw);
        assertTrue(result.isPresent());
        assertInstanceOf(ParsedVitalSign.class, result.get());

        ParsedVitalSign vs = (ParsedVitalSign) result.get();
        assertEquals("EVT-001", vs.eventId());
        assertEquals("DEV-001", vs.deviceId());
        assertEquals(75, vs.heartRate());
        assertEquals(16, vs.respiratoryRate());
        assertEquals(1, vs.bodyMovement());
        assertEquals("IN_BED", vs.bedStatus());
        assertNotNull(vs.rawPayload());
    }

    @Test
    void parse_sos() throws Exception {
        String json = """
            {
                "messageId": "msg-002",
                "deviceId": "DEV-SOS-01",
                "messageType": "SOS",
                "protocolVersion": "1.0",
                "occurredAt": "2026-07-24T02:30:00Z",
                "sequenceNo": 2,
                "payload": {
                    "triggerType": "BUTTON_PRESS",
                    "batteryLevel": 85
                }
            }
            """;
        JsonNode envelope = mapper.readTree(json);
        RawDeviceMessage raw = new RawDeviceMessage(
            "elder/P001/SOS_BUTTON/DEV-SOS-01/up/event",
            envelope, "P001", "SOS_BUTTON", "DEV-SOS-01", "SOS",
            "EVT-002", "trace-002", OffsetDateTime.parse("2026-07-24T02:30:00Z")
        );

        Optional<ParsedEvent> result = parser.parse(raw);
        assertTrue(result.isPresent());
        assertInstanceOf(ParsedSosEvent.class, result.get());

        ParsedSosEvent sos = (ParsedSosEvent) result.get();
        assertEquals("BUTTON_PRESS", sos.triggerType());
        assertEquals(85, sos.batteryLevel());
    }

    @Test
    void parse_heartbeat() throws Exception {
        String json = """
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
        JsonNode envelope = mapper.readTree(json);
        RawDeviceMessage raw = new RawDeviceMessage(
            "elder/P001/RADAR/DEV-001/up/heartbeat",
            envelope, "P001", "RADAR", "DEV-001", "HEARTBEAT",
            "EVT-003", "trace-003", OffsetDateTime.parse("2026-07-24T02:30:00Z")
        );

        Optional<ParsedEvent> result = parser.parse(raw);
        assertTrue(result.isPresent());
        assertInstanceOf(ParsedHeartbeat.class, result.get());
        assertEquals("DEV-001", result.get().deviceId());
    }

    @Test
    void parse_vital_sign_missing_fields() throws Exception {
        // Vital sign with missing optional fields — should still parse
        String json = """
            {
                "messageId": "msg-004",
                "deviceId": "DEV-002",
                "messageType": "VITAL_SIGN",
                "protocolVersion": "1.0",
                "occurredAt": "2026-07-24T02:30:00Z",
                "payload": { "heart_rate": 80 }
            }
            """;
        JsonNode envelope = mapper.readTree(json);
        RawDeviceMessage raw = new RawDeviceMessage(
            "elder/P001/MATTRESS/DEV-002/up/telemetry",
            envelope, "P001", "MATTRESS", "DEV-002", "VITAL_SIGN",
            "EVT-004", "trace-004", OffsetDateTime.parse("2026-07-24T02:30:00Z")
        );

        Optional<ParsedEvent> result = parser.parse(raw);
        assertTrue(result.isPresent());
        ParsedVitalSign vs = (ParsedVitalSign) result.get();
        assertEquals(80, vs.heartRate());
        assertNull(vs.respiratoryRate());
        assertNull(vs.bedStatus());
    }
}
