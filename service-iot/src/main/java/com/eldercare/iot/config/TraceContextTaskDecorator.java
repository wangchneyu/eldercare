package com.eldercare.iot.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Copies MDC to application-managed worker threads and restores the worker's
 * previous context afterwards so pooled threads cannot retain another request.
 */
public final class TraceContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> workerContext = MDC.getCopyOfContextMap();
            try {
                replaceContext(callerContext);
                task.run();
            } finally {
                replaceContext(workerContext);
            }
        };
    }

    private static void replaceContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
