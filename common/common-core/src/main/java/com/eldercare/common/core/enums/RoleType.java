package com.eldercare.common.core.enums;

import java.util.Arrays;

/** 人员账号角色；设备身份由 DeviceType 和设备凭证表达。 */
public enum RoleType {
    SUPER_ADMIN("SUPER_ADMIN", "平台管理员"),
    ORG_ADMIN("ORG_ADMIN", "机构管理员"),
    CARE_MANAGER("CARE_MANAGER", "照护主管"),
    CAREGIVER("CAREGIVER", "护理员"),
    DOCTOR("DOCTOR", "医生"),
    FAMILY_MEMBER("FAMILY_MEMBER", "家属"),
    ELDERLY("ELDERLY", "老人");

    private final String code;
    private final String description;

    RoleType(String code, String description) { this.code = code; this.description = description; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public static RoleType fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown role type code: " + code));
    }
}
