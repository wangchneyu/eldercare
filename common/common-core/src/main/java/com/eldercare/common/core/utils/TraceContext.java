package com.eldercare.common.core.utils;

import org.slf4j.MDC;

public class TraceContext {
    private static final String TRACE_ID_KEY = "traceId";

    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }
}