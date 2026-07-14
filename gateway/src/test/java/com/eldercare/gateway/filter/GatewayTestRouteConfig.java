package com.eldercare.gateway.filter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Gateway 集成测试专用路由配置 — 回显 X-User-* 请求头验证 JWT 透传
 */
@TestConfiguration
public class GatewayTestRouteConfig {

    @Bean
    RouteLocator testRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                // 通用测试路由 — 回显 JWT 透传的 X-User-* 头
                .route("test-downstream", r -> r
                        .path("/test-downstream/**")
                        .filters(f -> f.filter(echoHeadersFilter()))
                        .uri("http://0.0.0.0:1"))
                // 模拟管理后台路径（RBAC: ADMIN/OPERATOR 通过后才路由到这里）
                .route("test-admin", r -> r
                        .path("/admin/**")
                        .filters(f -> f.filter(echoHeadersFilter()))
                        .uri("http://0.0.0.0:1"))
                // 模拟家属端路径（RBAC: ADMIN/FAMILY 通过后才路由到这里）
                .route("test-family", r -> r
                        .path("/family/**")
                        .filters(f -> f.filter(echoHeadersFilter()))
                        .uri("http://0.0.0.0:1"))
                // 模拟老人端路径（RBAC: ADMIN/CAREGIVER 通过后才路由到这里）
                .route("test-elderly", r -> r
                        .path("/elderly/**")
                        .filters(f -> f.filter(echoHeadersFilter()))
                        .uri("http://0.0.0.0:1"))
                // 模拟服务端路径（RBAC: ADMIN/OPERATOR/CAREGIVER 通过后才路由到这里）
                .route("test-server", r -> r
                        .path("/server/**")
                        .filters(f -> f.filter(echoHeadersFilter()))
                        .uri("http://0.0.0.0:1"))
                // WebSocket 测试路由 — 模拟 ws 连接握手阶段的 JWT 校验
                .route("test-ws", r -> r
                        .path("/ws/test/**")
                        .filters(f -> f.filter(echoHeadersFilter()))
                        .uri("http://0.0.0.0:1"))
                .build();
    }

    /**
     * 自定义 GatewayFilter: 读取请求中的 X-User-* 头，以 JSON 格式写回响应体
     */
    private GatewayFilter echoHeadersFilter() {
        return (exchange, chain) -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            String username = exchange.getRequest().getHeaders().getFirst("X-Username");
            String roles = exchange.getRequest().getHeaders().getFirst("X-User-Roles");

            String body = String.format(
                    "{\"X-User-Id\":\"%s\",\"X-Username\":\"%s\",\"X-User-Roles\":\"%s\"}",
                    userId != null ? userId : "",
                    username != null ? username : "",
                    roles != null ? roles : "");

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        };
    }
}
