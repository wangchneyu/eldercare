package com.eldercare.common.core.enums;

import java.util.Arrays;

/** 初版照护等级示例；正式评估标准确定后应以其代码为准。 */
public enum CareLevel {
    SELF_CARE("SELF_CARE", "自理"),
    ASSISTED("ASSISTED", "需协助"),
    DEPENDENT("DEPENDENT", "失能照护"),
    DEMENTIA_CARE("DEMENTIA_CARE", "失智照护");

    private final String code;
    private final String description;

    CareLevel(String code, String description) { this.code = code; this.description = description; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public static CareLevel fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown care level code: " + code));
    }
}
