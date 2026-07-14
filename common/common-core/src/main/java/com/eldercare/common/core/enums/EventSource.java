package com.eldercare.common.core.enums;

import java.util.Arrays;

/** 业务事件来源。 */
public enum EventSource {
    DEVICE("DEVICE", "设备"),
    MANUAL("MANUAL", "人工"),
    SCHEDULE("SCHEDULE", "定时任务"),
    SYSTEM("SYSTEM", "内部系统"),
    THIRD_PARTY("THIRD_PARTY", "第三方");

    private final String code;
    private final String description;

    EventSource(String code, String description) { this.code = code; this.description = description; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public static EventSource fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown event source code: " + code));
    }
}
