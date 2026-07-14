package com.eldercare.common.core.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 日期时间工具。无时区时间默认按中国标准时间处理。 */
public final class DateTimeUtil {
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateTimeUtil() { }

    public static LocalDateTime now() { return LocalDateTime.now(DEFAULT_ZONE); }
    public static Instant nowUtc() { return Instant.now(); }
    public static String format(LocalDateTime value) { return value == null ? null : DATE_TIME_FORMATTER.format(value); }
    public static String format(LocalDate value) { return value == null ? null : DATE_FORMATTER.format(value); }
    public static LocalDateTime parseDateTime(String value) { return LocalDateTime.parse(value, DATE_TIME_FORMATTER); }
    public static LocalDate parseDate(String value) { return LocalDate.parse(value, DATE_FORMATTER); }

    public static boolean isOverdue(LocalDateTime deadline, LocalDateTime now) {
        Objects.requireNonNull(deadline, "deadline must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return deadline.isBefore(now);
    }

    public static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, DEFAULT_ZONE);
    }

    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(DEFAULT_ZONE).toInstant();
    }
}