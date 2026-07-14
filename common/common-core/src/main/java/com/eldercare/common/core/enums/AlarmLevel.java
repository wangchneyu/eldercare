package com.eldercare.common.core.enums;

import java.util.Arrays;

/** 告警严重等级，按声明顺序递增。 */
public enum AlarmLevel {
    INFO("INFO", "信息"),
    WARNING("WARNING", "预警"),
    URGENT("URGENT", "紧急"),
    CRITICAL("CRITICAL", "危急");

    private final String code;
    private final String description;

    AlarmLevel(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }

    public static AlarmLevel fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown alarm level code: " + code));
    }
}
