package com.eldercare.common.core.enums;

import java.util.Arrays;

/** 照护任务处理状态。 */
public enum TaskStatus {
    PENDING("PENDING", "待处理"),
    IN_PROGRESS("IN_PROGRESS", "处理中"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String description;

    TaskStatus(String code, String description) { this.code = code; this.description = description; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public static TaskStatus fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task status code: " + code));
    }
}
