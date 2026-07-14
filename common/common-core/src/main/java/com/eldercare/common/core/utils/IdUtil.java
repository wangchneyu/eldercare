package com.eldercare.common.core.utils;

import java.util.Objects;
import java.util.UUID;

/** 无状态全局唯一标识生成工具。 */
public final class IdUtil {
    private IdUtil() { }

    public static String uuid() { return UUID.randomUUID().toString(); }
    public static String uuid32() { return uuid().replace("-", ""); }

    public static String prefixed(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return prefix + "_" + uuid();
    }
}