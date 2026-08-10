package com.eldercare.iot.support;

import com.eldercare.common.core.utils.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Structured, WebFlux-safe audit logging for IoT write operations.
 * <p>
 * Never logs full request bodies, payloads or Idempotency-Key values; only
 * operation, resource identity, operator and trace identifiers.
 */
@Slf4j
@Component
public class IotAuditLogger {

    public void success(String operation, String resourceId, ServerHttpRequest request) {
        log.info("iot_audit operation={}, resourceId={}, userId={}, username={}, traceId={}",
                operation,
                resourceId,
                request.getHeaders().getFirst("X-User-Id"),
                request.getHeaders().getFirst("X-Username"),
                TraceContext.currentTraceId());
    }

    public void success(String operation, String resourceId, String modelCode, ServerHttpRequest request) {
        logModel("SUCCESS", operation, resourceId, modelCode, request);
    }

    public void failure(String operation, String resourceId, String modelCode, ServerHttpRequest request) {
        logModel("FAILURE", operation, resourceId, modelCode, request);
    }

    private void logModel(String result, String operation, String resourceId, String modelCode,
                          ServerHttpRequest request) {
        Object[] args = {
                operation,
                resourceId,
                modelCode == null ? "" : modelCode,
                request.getHeaders().getFirst("X-User-Id"),
                request.getHeaders().getFirst("X-Username"),
                TraceContext.currentTraceId(),
                result
        };
        if ("FAILURE".equals(result)) {
            log.warn("iot_audit operation={}, modelId={}, modelCode={}, userId={}, username={}, traceId={}, result={}",
                    args);
        } else {
            log.info("iot_audit operation={}, modelId={}, modelCode={}, userId={}, username={}, traceId={}, result={}",
                    args);
        }
    }
}
