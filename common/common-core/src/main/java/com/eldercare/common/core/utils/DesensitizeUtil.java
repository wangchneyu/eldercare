package com.eldercare.common.core.utils;

public class DesensitizeUtil {

    /**
     * 手机号脱敏 (保留前3后4，如：138****5678)
     */
    public static String mobilePhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 身份证脱敏 (保留前6后4，如：320101****1234)
     */
    public static String idCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return idCard;
        }
        int maskLen = idCard.length() - 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskLen; i++) {
            sb.append("*");
        }
        return idCard.substring(0, 6) + sb + idCard.substring(idCard.length() - 4);
    }

    /**
     * 姓名脱敏 (保留姓，名打码，如：张*、张**、司马**)
     */
    public static String name(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.substring(0, 1));
        for (int i = 1; i < name.length(); i++) {
            sb.append("*");
        }
        return sb.toString();
    }

    /**
     * 邮箱脱敏 (保留首字母和域名，如：a***@example.com)
     */
    public static String email(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf("@");
        String prefix = email.substring(0, atIndex);
        String suffix = email.substring(atIndex);
        if (prefix.length() <= 1) {
            return prefix + "***" + suffix;
        }
        return prefix.charAt(0) + "***" + suffix;
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