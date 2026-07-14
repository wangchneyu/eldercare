package com.eldercare.common.core.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TraceContextTest {

    @BeforeEach
    public void setUp() {
        TraceContext.setTraceId("test-trace-id-001");
    }

    @AfterEach
    public void tearDown() {
        TraceContext.clear();
    }

    @Test
    public void testCurrentTraceId() {
        Assertions.assertEquals("test-trace-id-001", TraceContext.currentTraceId());
    }

    @Test
    public void testSetTraceId() {
        TraceContext.setTraceId("new-trace-id");
        Assertions.assertEquals("new-trace-id", TraceContext.currentTraceId());
    }

    @Test
    public void testClear() {
        TraceContext.clear();
        Assertions.assertNull(TraceContext.currentTraceId());
    }

    @Test
    public void testGenerateTraceId() {
        String traceId = TraceContext.generateTraceId();
        Assertions.assertNotNull(traceId);
        Assertions.assertEquals(32, traceId.length()); // UUID 去横线后为 32 位
        Assertions.assertFalse(traceId.contains("-"));
    }

    @Test
    public void testGenerateTraceIdIsUnique() {
        String id1 = TraceContext.generateTraceId();
        String id2 = TraceContext.generateTraceId();
        Assertions.assertNotEquals(id1, id2);
    }
}
