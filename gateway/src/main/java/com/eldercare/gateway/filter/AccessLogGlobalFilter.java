package com.eldercare.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 全量请求访问日志全局过滤器（WebFlux / Gateway 专用）
 * <p>
 * 记录每个入站请求的关键信息：traceId、HTTP method、请求路径、响应状态码、耗时、客户端真实 IP。
 * <p>
 * 日志级别规则:
 * <ul>
 *   <li>正常请求（耗时 < 2s）→ INFO</li>
 *   <li>慢请求（耗时 >= 2s）→ WARN</li>
 *   <li>5xx 响应 → ERROR</li>
 * </ul>
 * <p>
 * 不记录: 请求体、响应体、Authorization Header、Cookie（避免敏感数据泄露 + 性能开销）
 */
@Slf4j
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    private static final long SLOW_REQUEST_THRESHOLD_MS = 2000L;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long duration = System.currentTimeMillis() - startTime;

            ServerHttpRequest request = exchange.getRequest();
            String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
            String path = request.getURI().getPath();
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            String traceId = exchange.getAttribute("traceId");
            String clientIp = resolveClientIp(request);

            // 结构化日志: [traceId] method path status duration_ms client_ip
            if (status >= 500) {
                log.error("[{}] {} {} {} {}ms {}", traceId, method, path, status, duration, clientIp);
            } else if (duration >= SLOW_REQUEST_THRESHOLD_MS) {
                log.warn("[{}] {} {} {} {}ms {}", traceId, method, path, status, duration, clientIp);
            } else {
                log.info("[{}] {} {} {} {}ms {}", traceId, method, path, status, duration, clientIp);
            }
        }));
    }

    @Override
    public int getOrder() {
        // 最高优先级，确保记录完整请求耗时（从进入网关到响应写出）
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * 从 X-Forwarded-For 提取客户端真实 IP，回退到 remoteAddress
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            // X-Forwarded-For 可能包含多个 IP，取第一个（最原始的客户端 IP）
            return xff.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
}
