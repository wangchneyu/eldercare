package com.eldercare.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 设备在线状态
 */
@Getter
@AllArgsConstructor
public enum OnlineStatus {

    ONLINE("ONLINE", "在线"),
    OFFLINE("OFFLINE", "离线"),
    UNKNOWN("UNKNOWN", "未知");

    private final String code;
    private final String description;

    public static OnlineStatus fromCode(String code) {
        for (OnlineStatus status : values()) {
            if (status.code.equals(code)) return status;
        }
        throw new IllegalArgumentException("Unknown OnlineStatus: " + code);
    }
}
