package com.eldercare.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TraceIdGlobalFilter 单元测试
 */
class TraceIdGlobalFilterTest {

    private TraceIdGlobalFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new TraceIdGlobalFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void shouldGenerateTraceIdWhenNoHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        String traceId = exchange.getAttribute("traceId");
        assertNotNull(traceId);
        assertEquals(32, traceId.length());
        assertTrue(traceId.matches("^[a-f0-9]{32}$"));
    }

    @Test
    void shouldPassThroughValidTraceId() {
        String validTraceId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Trace-Id", validTraceId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        String traceId = exchange.getAttribute("traceId");
        assertEquals(validTraceId, traceId);
    }

    @Test
    void shouldRegenerateWhenInvalidFormat() {
        // 含特殊字符（日志注入攻击）
        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Trace-Id", "invalid\ninjection\rattack")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        String traceId = exchange.getAttribute("traceId");
        assertNotNull(traceId);
        assertEquals(32, traceId.length());
        assertNotEquals("invalid\ninjection\rattack", traceId);
    }

    @Test
    void shouldRegenerateWhenTooLong() {
        String tooLong = "a".repeat(100);
        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Trace-Id", tooLong)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        String traceId = exchange.getAttribute("traceId");
        assertNotNull(traceId);
        assertEquals(32, traceId.length());
    }

    @Test
    void shouldSetResponseHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        String responseTraceId = exchange.getResponse().getHeaders().getFirst("X-Trace-Id");
        assertNotNull(responseTraceId);
        assertEquals(32, responseTraceId.length());
    }

    @Test
    void shouldSetDownstreamRequestHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        // 验证下游请求头中包含 X-Trace-Id
        String downstreamTraceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        assertNotNull(downstreamTraceId);
        assertEquals(exchange.getAttribute("traceId"), downstreamTraceId);
    }

    @Test
    void shouldHaveCorrectOrder() {
        assertEquals(-110, filter.getOrder());
    }
}
