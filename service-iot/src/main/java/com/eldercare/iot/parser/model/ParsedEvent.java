package com.eldercare.iot.parser.model;

import java.time.OffsetDateTime;

public sealed interface ParsedEvent permits ParsedVitalSign, ParsedSosEvent, ParsedHeartbeat {
    String eventId();
    String sourceMessageId();
    String deviceId();
    OffsetDateTime occurredAt();
    String traceId();
}
