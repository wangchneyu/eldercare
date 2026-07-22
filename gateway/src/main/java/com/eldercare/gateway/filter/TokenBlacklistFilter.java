package com.eldercare.gateway.filter;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Token 黑名单全局过滤器（WebFlux / Gateway 专用）
 * <p>
 * 在 JWT 密码学验签通过后，额外检查 Redis 中是否存在该 Token 的黑名单记录。
 * <ul>
 *   <li>登出黑名单: {@code eldercare:auth:token:blacklist:{jti}}（TTL = Token 剩余有效期）</li>
 *   <li>封禁黑名单: {@code eldercare:auth:token:blacklist:user:{userId}}（无 TTL，手动删除解封）</li>
 * </ul>
 * <p>
 * 性能约束: Redis 查询增加延迟 <= 2ms (P99)。
 * 降级策略: Redis 不可用时降级为仅做 JWT 密码学验签（WARN 日志记录），不阻断请求。
 * <p>
 * 执行顺序: JwtAuthGlobalFilter(-100) → TokenBlacklistFilter(-99) → RbacAuthGlobalFilter(-95)
 */
@Slf4j
@Component
@ConditionalOnBean(ReactiveStringRedisTemplate.class)
public class TokenBlacklistFilter implements GlobalFilter, Ordered {

    private static final String KEY_PREFIX_TOKEN = "eldercare:auth:token:blacklist:";
    private static final String KEY_PREFIX_USER = "eldercare:auth:token:blacklist:user:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public TokenBlacklistFilter(ReactiveStringRedisTemplate redisTemplate,
                                JwtTokenProvider jwtTokenProvider,
                                ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 仅对已通过 JWT 验签的请求检查黑名单（白名单路径无 loginUser，直接跳过）
        LoginUser loginUser = exchange.getAttribute("loginUser");
        if (loginUser == null) {
            return chain.filter(exchange);
        }

        String rawToken = exchange.getAttribute("rawToken");
        String userId = String.valueOf(loginUser.getUserId());

        // 提取 JTI（JWT ID）
        String jti = extractJti(rawToken);

        // 并行检查两个黑名单 key
        String tokenKey = KEY_PREFIX_TOKEN + (jti != null ? jti : "invalid");
        String userKey = KEY_PREFIX_USER + userId;

        return redisTemplate.hasKey(tokenKey)
                .zipWith(redisTemplate.hasKey(userKey))
                .flatMap(tuple -> {
                    boolean tokenBlacklisted = Boolean.TRUE.equals(tuple.getT1());
                    boolean userBlacklisted = Boolean.TRUE.equals(tuple.getT2());

                    if (tokenBlacklisted || userBlacklisted) {
                        log.info("Token 黑名单命中: userId={}, jti={}, tokenBlacklisted={}, userBlacklisted={}",
                                userId, jti, tokenBlacklisted, userBlacklisted);
                        return writeUnauthorized(exchange);
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    // Redis 不可用时降级为仅 JWT 验签，不阻断请求
                    log.warn("Token 黑名单 Redis 查询失败，降级为仅 JWT 验签: {}", e.getMessage());
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        // 在 JwtAuthGlobalFilter(-100) 之后，RbacAuthGlobalFilter(-95) 之前
        return -99;
    }

    /**
     * 从 Token 中提取 JTI（JWT ID）
     */
    private String extractJti(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            Claims claims = jwtTokenProvider.getClaims(token);
            return claims.getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 返回 401 JSON 错误响应
     */
    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

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
