package com.eldercare.common.core.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
public class DesensitizeUtilTest {

    @Test
    public void testMobilePhone() {
        Assertions.assertEquals("138****5678", DesensitizeUtil.mobilePhone("13812345678"));
        Assertions.assertNull(DesensitizeUtil.mobilePhone(null));
        Assertions.assertEquals("123456", DesensitizeUtil.mobilePhone("123456")); // 非11位不处理
    }

    @Test
    public void testIdCard() {
        // 18位身份证: 保留前6后4，中间 18-10=8 个星号
        Assertions.assertEquals("320101********1234", DesensitizeUtil.idCard("320101199001011234"));
        Assertions.assertEquals("320101********5678", DesensitizeUtil.idCard("320101199001015678"));
        Assertions.assertNull(DesensitizeUtil.idCard(null));
        Assertions.assertEquals("12345", DesensitizeUtil.idCard("12345")); // 短于8位不处理
    }

    @Test
    public void testName() {
        Assertions.assertEquals("张*", DesensitizeUtil.name("张三"));
        Assertions.assertEquals("张**", DesensitizeUtil.name("张三丰"));
        Assertions.assertEquals("李", DesensitizeUtil.name("李")); // 单字不变
        Assertions.assertNull(DesensitizeUtil.name(null));
        Assertions.assertEquals("", DesensitizeUtil.name(""));
    }

    @Test
    public void testEmail() {
        Assertions.assertEquals("a***@example.com", DesensitizeUtil.email("abc@example.com"));
        Assertions.assertEquals("t***@test.org", DesensitizeUtil.email("test@test.org"));
        Assertions.assertEquals("a***@x.com", DesensitizeUtil.email("a@x.com"));
        Assertions.assertNull(DesensitizeUtil.email(null));
        Assertions.assertEquals("noemail", DesensitizeUtil.email("noemail")); // 无@不处理
    }

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