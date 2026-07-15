package com.eldercare.gateway.filter;

import com.eldercare.common.security.domain.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 连接数限制过滤器
 * <p>
 * 限制每个用户最多同时保持 3 个 WebSocket 连接。
 * 在 JwtAuthGlobalFilter（order=-100）之后执行（order=-90），
 * 依赖其通过 exchange.attributes 设置的 userId。
 * <p>
 * 此过滤器独立于 Sentinel 的 HTTP 限流，仅对 WebSocket 升级请求生效。
 */
@Slf4j
@Component
public class WebSocketConnectionLimitFilter implements GlobalFilter, Ordered {

    /** 每用户最大 WebSocket 并发连接数 */
    private static final int MAX_CONNECTIONS_PER_USER = 3;

    /** userId → 当前连接计数（线程安全） */
    private final Map<String, AtomicInteger> userConnectionCounts = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 仅处理 WebSocket 升级请求
        ServerHttpRequest request = exchange.getRequest();
        String upgrade = request.getHeaders().getFirst("Upgrade");
        if (!"websocket".equalsIgnoreCase(upgrade)) {
            return chain.filter(exchange);
        }

        // 从 JwtAuthGlobalFilter 透传的 attributes 中获取 LoginUser
        LoginUser loginUser = exchange.getAttribute("loginUser");
        if (loginUser == null) {
            // 无用户信息（白名单路径的 WebSocket 或未认证），放行
            return chain.filter(exchange);
        }

        String userId = String.valueOf(loginUser.getUserId());

        AtomicInteger count = userConnectionCounts.computeIfAbsent(userId, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();

        if (current > MAX_CONNECTIONS_PER_USER) {
            count.decrementAndGet();
            log.warn("用户 {} WebSocket 连接数超限: {}", userId, current);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }

        // 连接关闭时递减计数（通过 beforeCommit 钩子）
        exchange.getResponse().beforeCommit(() -> {
            AtomicInteger c = userConnectionCounts.get(userId);
            if (c != null) {
                int remaining = c.decrementAndGet();
                log.debug("用户 {} WebSocket 连接关闭，剩余连接数: {}", userId, remaining);
                // 清理无连接的条目，防止 Map 无限增长
                if (remaining <= 0) {
                    userConnectionCounts.remove(userId);
                }
            }
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 在 JwtAuthGlobalFilter (-100) 之后执行
        return -90;
    }
}
