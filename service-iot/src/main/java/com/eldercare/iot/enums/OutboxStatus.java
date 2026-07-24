package com.eldercare.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outbox 发送状态
 */
@Getter
@AllArgsConstructor
public enum OutboxStatus {

    PENDING("PENDING", "待发送"),
    SENT("SENT", "已发送"),
    FAILED("FAILED", "发送失败");

    private final String code;
    private final String description;

    public static OutboxStatus fromCode(String code) {
        for (OutboxStatus status : values()) {
            if (status.code.equals(code)) return status;
        }
        throw new IllegalArgumentException("Unknown OutboxStatus: " + code);
    }
}
