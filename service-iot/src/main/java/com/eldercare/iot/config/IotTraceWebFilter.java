package com.eldercare.iot.config;

import com.eldercare.common.core.utils.TraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * Establishes a trace id for every REST request before WebFlux offloads a
 * blocking controller method to the iot-blocking executor.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IotTraceWebFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = resolveTraceId(exchange.getRequest());
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);

        return Mono.defer(() -> {
            String previousTraceId = TraceContext.currentTraceId();
            TraceContext.setTraceId(traceId);
            return chain.filter(exchange)
                    .doFinally(signalType -> restoreTraceId(previousTraceId));
        });
    }

    private static String resolveTraceId(ServerHttpRequest request) {
        String candidate = request.getHeaders().getFirst(TRACE_ID_HEADER);
        return candidate != null && TRACE_ID_PATTERN.matcher(candidate).matches()
                ? candidate
                : TraceContext.generateTraceId();
    }

    private static void restoreTraceId(String previousTraceId) {
        if (previousTraceId == null) {
            TraceContext.clear();
        } else {
            TraceContext.setTraceId(previousTraceId);
        }
    }
}
