package com.eldercare.gateway.filter;

import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TokenBlacklistFilter 单元测试
 */
class TokenBlacklistFilterTest {

    private TokenBlacklistFilter filter;
    private ReactiveStringRedisTemplate redisTemplate;
    private JwtTokenProvider jwtTokenProvider;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        jwtTokenProvider = new JwtTokenProvider("TestSecretKeyForUnitTest1234567890", 7200, 604800);
        filter = new TokenBlacklistFilter(redisTemplate, jwtTokenProvider, new ObjectMapper());
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void shouldPassThroughWhenNoLoginUser() {
        // 白名单路径无 loginUser，直接放行
        MockServerHttpRequest request = MockServerHttpRequest.get("/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void shouldReturn401WhenUserBlacklisted() {
        LoginUser loginUser = new LoginUser(1L, "bannedUser", Set.of(UserRole.ADMIN));
        String token = jwtTokenProvider.createAccessToken(loginUser);

        MockServerHttpRequest request = MockServerHttpRequest.get("/admin/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("loginUser", loginUser);
        exchange.getAttributes().put("rawToken", token);
        exchange.getAttributes().put("traceId", "abc123def456abc123def456abc12345");

        // Token JTI 不在黑名单，但 userId 在黑名单
        when(redisTemplate.hasKey(startsWith("eldercare:auth:token:blacklist:")))
                .thenReturn(Mono.just(false));
        when(redisTemplate.hasKey("eldercare:auth:token:blacklist:user:1"))
                .thenReturn(Mono.just(true));

        filter.filter(exchange, chain).block();

        // 应返回 401，不调用 chain
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    void shouldPassThroughWhenNotBlacklisted() {
        LoginUser loginUser = new LoginUser(2L, "normalUser", Set.of(UserRole.CAREGIVER));
        String token = jwtTokenProvider.createAccessToken(loginUser);

        MockServerHttpRequest request = MockServerHttpRequest.get("/elderly/vitals").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("loginUser", loginUser);
        exchange.getAttributes().put("rawToken", token);

        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void shouldDegradeWhenRedisUnavailable() {
        LoginUser loginUser = new LoginUser(3L, "user", Set.of(UserRole.FAMILY));
        String token = jwtTokenProvider.createAccessToken(loginUser);

        MockServerHttpRequest request = MockServerHttpRequest.get("/family/data").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("loginUser", loginUser);
        exchange.getAttributes().put("rawToken", token);

        // Redis 不可用
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        // 降级放行，不阻断请求
        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void shouldHaveCorrectOrder() {
        assertEquals(-99, filter.getOrder());
    }
}
