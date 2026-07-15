package com.eldercare.common.core.utils;

import java.util.Objects;
import java.util.UUID;

/** 无状态全局唯一标识生成工具。 */
public final class IdUtil {
    private IdUtil() { }

    // ==================== Snowflake ====================
    private static final long START_EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC
    private static final long WORKER_ID_BITS = 10L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS); // 1023
    private static final long SEQUENCE_BITS = 12L;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS); // 4095

    private static long workerId = -1L;
    private static long sequence = 0L;
    private static long lastTimestamp = -1L;

    public static String uuid() { return UUID.randomUUID().toString(); }
    public static String uuid32() { return uuid().replace("-", ""); }

    public static String prefixed(String prefix) {
        Objects.requireNonNull(prefix, "前缀不能为 null");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("前缀不能为空白字符串");
        }
        return prefix + "_" + uuid();
    }

    public static synchronized void initSnowflake(int workerIdVal) {
        if (workerIdVal < 0 || workerIdVal > MAX_WORKER_ID) {
            throw new IllegalArgumentException(String.format("工作机器 ID 不能大于 %d 或小于 0", MAX_WORKER_ID));
        }
        workerId = workerIdVal;
    }

    public static synchronized Long nextId() {
        if (workerId == -1) {
            workerId = 0L;
        }

        long timestamp = timeGen();

        if (timestamp < lastTimestamp) {
            throw new RuntimeException(String.format("系统时钟回拨，拒绝为 %d 毫秒内的请求生成 ID", lastTimestamp - timestamp));
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - START_EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private static long tilNextMillis(long lastTimestampVal) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestampVal) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private static long timeGen() {
        return System.currentTimeMillis();
    }
}