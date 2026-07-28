package com.eldercare.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Status of a C04 message after its foreground retries have been exhausted. */
@Getter
@AllArgsConstructor
public enum VitalDeliveryStatus {

    PENDING("PENDING"),
    SENT("SENT"),
    QUARANTINED("QUARANTINED");

    private final String code;
}
