package com.eldercare.common.core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesensitizeUtilTest {
    @Test
    void shouldMaskCommonPersonalData() {
        assertEquals("138****5678", DesensitizeUtil.mobilePhone("13812345678"));
        assertEquals("张*", DesensitizeUtil.name("张三"));
        assertEquals("110101********1234", DesensitizeUtil.idCard("110101199001011234"));
        assertEquals("z***@example.com", DesensitizeUtil.email("zhang@example.com"));
        assertEquals("**** **** **** 1234", DesensitizeUtil.bankCard("6222021234561234"));
    }

    @Test
    void shouldKeepInvalidOrNullValuesUnchanged() {
        assertEquals(null, DesensitizeUtil.mobilePhone(null));
        assertEquals("123", DesensitizeUtil.mobilePhone("123"));
        assertEquals("invalid", DesensitizeUtil.email("invalid"));
        assertEquals("1234", DesensitizeUtil.bankCard("1234"));
    }
}