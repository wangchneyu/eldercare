package com.eldercare.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 设备生命周期状态
 */
@Getter
@AllArgsConstructor
public enum LifecycleStatus {

    ACTIVE("ACTIVE", "启用"),
    DISABLED("DISABLED", "停用"),
    RETIRED("RETIRED", "退役");

    private final String code;
    private final String description;

    public static LifecycleStatus fromCode(String code) {
        for (LifecycleStatus status : values()) {
            if (status.code.equals(code)) return status;
        }
        throw new IllegalArgumentException("Unknown LifecycleStatus: " + code);
    }
}
