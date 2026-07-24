package com.eldercare.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 设备绑定类型
 */
@Getter
@AllArgsConstructor
public enum BindingType {

    ELDER("ELDER", "长者绑定"),
    LOCATION("LOCATION", "位置绑定");

    private final String code;
    private final String description;

    public static BindingType fromCode(String code) {
        for (BindingType type : values()) {
            if (type.code.equals(code)) return type;
        }
        throw new IllegalArgumentException("Unknown BindingType: " + code);
    }
}
