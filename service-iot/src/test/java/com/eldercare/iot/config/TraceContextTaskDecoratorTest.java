package com.eldercare.iot.config;

import com.eldercare.common.core.utils.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceContextTaskDecoratorTest {

    @Test
    void propagatesCallerTraceAndRestoresWorkerContext() {
        TraceContextTaskDecorator decorator = new TraceContextTaskDecorator();
        AtomicReference<String> observedTraceId = new AtomicReference<>();
        TraceContext.setTraceId("caller-trace");
        Runnable decorated = decorator.decorate(() -> observedTraceId.set(TraceContext.currentTraceId()));

        TraceContext.setTraceId("worker-trace");
        try {
            decorated.run();
            assertEquals("caller-trace", observedTraceId.get());
            assertEquals("worker-trace", TraceContext.currentTraceId());
        } finally {
            TraceContext.clear();
        }
    }
}
