package com.eldercare.common.core.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DesensitizeUtilTest {

    @Test
    public void testMobilePhone() {
        String phone = "13812345678";
        String result = DesensitizeUtil.mobilePhone(phone);
        // 断言验证脱敏结果是否正确
        Assertions.assertEquals("138****5678", result);
        System.out.println("手机号脱敏测试通过: " + result);
    }
}