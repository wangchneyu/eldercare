package com.eldercare.iot.parser.model;

import java.time.OffsetDateTime;
import java.util.Map;

public record ParsedVitalSign(
    String eventId,
    String sourceMessageId,
    String deviceId,
    OffsetDateTime occurredAt,
    String traceId,
    String parkId,
    String deviceType,
    // Vital sign fields (snake_case, nullable)
    Integer heartRate,
    Integer respiratoryRate,
    Integer bodyMovement,
    String bedStatus,
    // Raw payload for forwarding
    Map<String, Object> rawPayload
) implements ParsedEvent {}
