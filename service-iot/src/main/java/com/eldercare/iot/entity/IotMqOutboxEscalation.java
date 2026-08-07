package com.eldercare.iot.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * C16-3: read-only projection returned by the one-shot escalation UPDATE.
 * <p>
 * Carries exactly the audit fields needed for the structured escalation log
 * (traceId is extracted from {@code raw_envelope->>'traceId'} in SQL, so the
 * full {@code rawEnvelopeJson} is never loaded or logged).
 */
@Data
public class IotMqOutboxEscalation implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String eventId;

    private String deviceId;

    private String eventType;

    private OffsetDateTime createdAt;

    private OffsetDateTime escalatedAt;

    private Integer retryCount;

    private String lastError;

    private String traceId;
}
