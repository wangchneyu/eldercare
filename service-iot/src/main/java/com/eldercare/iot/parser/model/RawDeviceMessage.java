package com.eldercare.iot.parser.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record RawDeviceMessage(
    String topic,
    JsonNode envelope,
    String parkId,
    String deviceType,
    String deviceId,
    String messageType,
    String eventId,
    String traceId,
    OffsetDateTime occurredAt
) {}
