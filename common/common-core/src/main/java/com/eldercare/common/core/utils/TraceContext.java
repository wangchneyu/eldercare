package com.eldercare.common.core.utils;

import org.slf4j.MDC;
import java.util.UUID;

public class TraceContext {

    private static final String TRACE_ID_KEY = "traceId";

    /**
     * 获取当前线程的 traceId
     */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 设置当前线程的 traceId
     */
    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /**
     * 清除当前线程的 traceId
     */
    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }

    /**
     * 生成一个新的 traceId（32 位 UUID 无横线）
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}