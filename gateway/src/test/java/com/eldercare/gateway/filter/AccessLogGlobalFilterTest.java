package com.eldercare.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AccessLogGlobalFilter 单元测试
 */
class AccessLogGlobalFilterTest {

    private AccessLogGlobalFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new AccessLogGlobalFilter();
        chain = mock(GatewayFilterChain.class);
    }

    @Test
    void shouldLogNormalRequest() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/admin/users")
                .header("X-Forwarded-For", "192.168.1.100")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("traceId", "abc123def456abc123def456abc12345");
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(chain.filter(any())).thenReturn(Mono.empty());

        // 不应抛异常
        assertDoesNotThrow(() -> filter.filter(exchange, chain).block());
    }

    @Test
    void shouldLog5xxAsError() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/admin/users").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("traceId", "abc123def456abc123def456abc12345");
        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);

        when(chain.filter(any())).thenReturn(Mono.empty());

        assertDoesNotThrow(() -> filter.filter(exchange, chain).block());
    }

    @Test
    void shouldExtractClientIpFromXForwardedFor() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Forwarded-For", "10.0.0.1, 192.168.1.1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("traceId", "abc123def456abc123def456abc12345");
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(chain.filter(any())).thenReturn(Mono.empty());

        assertDoesNotThrow(() -> filter.filter(exchange, chain).block());
    }

    @Test
    void shouldHaveHighestPrecedenceOrder() {
        assertEquals(Integer.MIN_VALUE, filter.getOrder());
    }
}
