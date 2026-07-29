package com.eldercare.iot.support;

import com.eldercare.common.core.utils.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/** Structured, WebFlux-safe audit logging for IoT write operations. */
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
}
