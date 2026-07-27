package com.eldercare.iot.parser;

import com.eldercare.iot.parser.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Component
public class SimulatorParser implements DeviceMessageParser {

    @Override
    public String parserCode() {
        return "simulator";
    }

    @Override
    public Optional<ParsedEvent> parse(RawDeviceMessage raw) {
        JsonNode payloadNode = raw.envelope().has("payload") ? raw.envelope().get("payload") : raw.envelope();

        return switch (raw.messageType()) {
            case "VITAL_SIGN" -> parseVitalSign(raw, payloadNode);
            case "SOS" -> parseSos(raw, payloadNode);
            case "FALL" -> parseFall(raw, payloadNode);
            case "HEARTBEAT" -> parseHeartbeat(raw);
            default -> {
                log.warn("不支持的 messageType: {}", raw.messageType());
                yield Optional.empty();
            }
        };
    }

    private Optional<ParsedEvent> parseVitalSign(RawDeviceMessage raw, JsonNode payload) {
        Map<String, Object> rawMap = jsonNodeToMap(payload);
        return Optional.of(new ParsedVitalSign(
                raw.eventId(),
                raw.envelope().get("messageId").asText(),
                raw.deviceId(),
                raw.occurredAt(),
                raw.traceId(),
                raw.parkId(),
                raw.deviceType(),
                null, // elderId 由 DisruptorEventHandler 注入
                intOrNull(payload, "heart_rate"),
                intOrNull(payload, "respiratory_rate"),
                intOrNull(payload, "body_movement"),
                textOrNull(payload, "bed_status"),
                rawMap
        ));
    }

    private Optional<ParsedEvent> parseSos(RawDeviceMessage raw, JsonNode payload) {
        return parseSosLike(raw, payload, "SOS_TRIGGERED");
    }

    private Optional<ParsedEvent> parseFall(RawDeviceMessage raw, JsonNode payload) {
        return parseSosLike(raw, payload, "FALL_DETECTED");
    }

    private Optional<ParsedEvent> parseSosLike(RawDeviceMessage raw, JsonNode payload, String eventType) {
        Map<String, Object> rawMap = jsonNodeToMap(payload);
        return Optional.of(new ParsedSosEvent(
                raw.eventId(),
                raw.envelope().get("messageId").asText(),
                raw.deviceId(),
                raw.occurredAt(),
                raw.traceId(),
                raw.parkId(),
                raw.deviceType(),
                eventType,
                null, null, null, null, null, null, null, // 绑定快照由 Handler 注入
                textOrNull(payload, "triggerType"),
                intOrNull(payload, "batteryLevel"),
                rawMap
        ));
    }

    private Optional<ParsedEvent> parseHeartbeat(RawDeviceMessage raw) {
        return Optional.of(new ParsedHeartbeat(
                raw.eventId(),
                raw.envelope().get("messageId").asText(),
                raw.deviceId(),
                raw.occurredAt(),
                raw.traceId(),
                raw.parkId(),
                raw.deviceType()
        ));
    }

    // Helper methods
    private Integer intOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asInt() : null;
    }

    private String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
