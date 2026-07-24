package com.eldercare.iot.parser.model;

import java.time.OffsetDateTime;
import java.util.Map;

public record ParsedSosEvent(
    String eventId,
    String sourceMessageId,
    String deviceId,
    OffsetDateTime occurredAt,
    String traceId,
    String parkId,
    String deviceType,
    String triggerType,
    Integer batteryLevel,
    Map<String, Object> rawPayload
) implements ParsedEvent {}
