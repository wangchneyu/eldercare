package com.eldercare.common.core.utils;

public class DesensitizeUtil {

    /**
     * 手机号脱敏 (保留前3后4，中间变星号。如：138****5678)
     */
    public static String mobilePhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}