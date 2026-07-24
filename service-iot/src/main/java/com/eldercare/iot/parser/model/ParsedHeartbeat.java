package com.eldercare.iot.parser.model;

import java.time.OffsetDateTime;

public record ParsedHeartbeat(
    String eventId,
    String sourceMessageId,
    String deviceId,
    OffsetDateTime occurredAt,
    String traceId,
    String parkId,
    String deviceType
) implements ParsedEvent {}
