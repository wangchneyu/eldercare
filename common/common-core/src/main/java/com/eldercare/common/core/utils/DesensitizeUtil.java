package com.eldercare.common.core.utils;

public final class DesensitizeUtil {
    private DesensitizeUtil() { }

    /** 手机号脱敏：保留前三、后四位，例如 138****5678。 */
    public static String mobilePhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    public static String name(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.charAt(0) + "*".repeat(value.length() - 1);
    }

    public static String idCard(String value) {
        if (value == null || value.length() < 10) {
            return value;
        }
        return value.substring(0, 6) + "*".repeat(value.length() - 10) + value.substring(value.length() - 4);
    }

    public static String email(String value) {
        if (value == null) {
            return null;
        }
        int atIndex = value.indexOf('@');
        if (atIndex <= 0 || atIndex == value.length() - 1) {
            return value;
        }
        return value.charAt(0) + "***" + value.substring(atIndex);
    }

    public static String bankCard(String value) {
        if (value == null || value.length() < 8) {
            return value;
        }
        String lastFour = value.substring(value.length() - 4);
        int groups = Math.max(1, (value.length() - 4 + 3) / 4);
        return "**** ".repeat(groups) + lastFour;
    }
}