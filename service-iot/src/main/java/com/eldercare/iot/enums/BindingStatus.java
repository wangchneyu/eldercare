package com.eldercare.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 绑定记录状态
 */
@Getter
@AllArgsConstructor
public enum BindingStatus {

    ACTIVE("ACTIVE", "生效中"),
    INACTIVE("INACTIVE", "已失效");

    private final String code;
    private final String description;

    public static BindingStatus fromCode(String code) {
        for (BindingStatus status : values()) {
            if (status.code.equals(code)) return status;
        }
        throw new IllegalArgumentException("Unknown BindingStatus: " + code);
    }
}
