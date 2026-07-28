package com.eldercare.iot.mq;

/** Frozen C04 send material retained across foreground and durable retries. */
record VitalDeliveryMessage(
        String eventId,
        String sourceMessageId,
        String deviceId,
        String deviceType,
        String traceId,
        String rawEnvelopeJson) {
}
