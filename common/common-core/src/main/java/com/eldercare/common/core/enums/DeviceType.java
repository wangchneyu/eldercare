package com.eldercare.common.core.enums;

import java.util.Arrays;

/** 设备物理类型，不作为人员角色使用。 */
public enum DeviceType {
    TV("TV", "智慧电视"),
    SMART_WATCH("SMART_WATCH", "智能手表"),
    VITAL_SIGN_MONITOR("VITAL_SIGN_MONITOR", "生命体征设备"),
    SOS_BUTTON("SOS_BUTTON", "紧急按钮"),
    GATEWAY("GATEWAY", "网关"),
    CAMERA("CAMERA", "摄像头"),
    OTHER("OTHER", "其他");

    private final String code;
    private final String description;

    DeviceType(String code, String description) { this.code = code; this.description = description; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public static DeviceType fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown device type code: " + code));
    }
}
