package com.eldercare.gateway.filter;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.security.config.SecurityProperties;
import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 鉴权全局过滤器（WebFlux / Gateway 专用）
 * <p>
 * 职责:
 * <ol>
 *   <li>白名单路径（/auth/**）直接放行</li>
 *   <li>从 Authorization 头或 query param ?token= 提取 Bearer Token</li>
 *   <li>调用 JwtTokenProvider 验证 Token 有效性</li>
 *   <li>解析 LoginUser，将 userId/username/roles 以自定义头透传给下游</li>
 *   <li>清除外部传入的 X-User-* 头（防伪造）</li>
 * </ol>
 * <p>
 * WebSocket 握手升级请求也走此过滤器 — 浏览器 WebSocket API 无法设置自定义头，故同时支持从
 * query param ?token=xxx 提取 Token。
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final SecurityProperties securityProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 透传给下游的用户信息头名称 */
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLES = "X-User-Roles";

    public JwtAuthGlobalFilter(JwtTokenProvider jwtTokenProvider, ObjectMapper objectMapper,
                               SecurityProperties securityProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 白名单路径直接放行（traceId 已由 TraceIdGlobalFilter 透传）
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        // 2. 提取 Token
        String token = extractToken(exchange);
        if (!StringUtils.hasText(token)) {
            return writeUnauthorized(exchange);
        }

        // 3. 验证 Token 有效性
        if (!jwtTokenProvider.validateToken(token)) {
            return writeUnauthorized(exchange);
        }

        // 4. 解析用户信息
        LoginUser loginUser = jwtTokenProvider.getLoginUser(token);
        if (loginUser == null) {
            return writeUnauthorized(exchange);
        }

        // 5. 存储到 exchange attributes（供同网关内其他 filter 使用）
        exchange.getAttributes().put("loginUser", loginUser);
        exchange.getAttributes().put("rawToken", token);

        // 6. 透传用户信息给下游服务 — 先清除外部传入的 X-User-* 头（防伪造），再设置真实值
        String rolesStr = loginUser.getRoles() != null
                ? String.join(",", loginUser.getRoles().stream().map(Enum::name).toList())
                : "";

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USERNAME);
                    headers.remove(HEADER_USER_ROLES);
                })
                .header(HEADER_USER_ID, String.valueOf(loginUser.getUserId()))
                .header(HEADER_USERNAME, loginUser.getUsername())
                .header(HEADER_USER_ROLES, rolesStr)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        // 高优先级，确保在其他业务过滤器之前执行
        return -100;
    }

    // ==================== 内部方法 ====================

    /**
     * 提取 Token: HTTP 从 Authorization 头提取，WebSocket 从查询参数提取
     */
    private String extractToken(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        // HTTP 方式: Authorization: Bearer <token>
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            return authHeader.substring(SecurityConstants.TOKEN_PREFIX.length());
        }

        // WebSocket 方式: ?token=<token>（浏览器 WebSocket API 无法设置自定义头）
        String tokenParam = request.getQueryParams().getFirst("token");
        if (StringUtils.hasText(tokenParam)) {
            return tokenParam;
        }

        return null;
    }

    /**
     * 判断请求路径是否在白名单中（支持 Ant 风格路径匹配，从 SecurityProperties 动态读取）
     */
    private boolean isWhitelisted(String path) {
        List<String> whitelist = securityProperties.getWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        return whitelist.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 返回 401 JSON 错误响应，格式与 common-security 保持一致
     * <p>
     * 响应体文案固定由 110001 错误码定义。
     */
    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // 从 exchange attribute 获取 traceId（由 TraceIdGlobalFilter 设置）
        String traceId = exchange.getAttribute("traceId");
        R<Void> result = R.fail(SystemErrorCode.UNAUTHORIZED);
        if (StringUtils.hasText(traceId)) {
            result.setTraceId(traceId);
        }
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            log.error("序列化 401 响应失败", e);
            return response.setComplete();
        }
    }
}
