package com.eldercare.gateway.filter;

import com.eldercare.common.core.utils.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * 全链路 traceId 全局过滤器（WebFlux / Gateway 专用）
 * <p>
 * 职责:
 * <ol>
 *   <li>若请求头不带 X-Trace-Id，则生成全局唯一 ID（UUID 无横线格式）；若带则透传</li>
 *   <li>防伪造：先移除外部传入的 X-Trace-Id，再设置为可信值</li>
 *   <li>将 traceId 存入 exchange attributes，供后续 Filter 和错误处理器使用</li>
 *   <li>将 traceId 通过请求头透传给下游微服务</li>
 * </ol>
 * <p>
 * 执行顺序: TraceIdGlobalFilter(-110) → JwtAuthGlobalFilter(-100) → RbacAuthGlobalFilter(-95) → WebSocketConnectionLimitFilter(-90)
 * <p>
 * 确保鉴权过滤器写 401 响应时 traceId 已就绪，白名单路径也能透传 traceId。
 */
@Slf4j
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    private static final String HEADER_TRACE_ID = "X-Trace-Id";
    private static final String ATTR_TRACE_ID = "traceId";
    /** 合法 traceId 格式：32 位十六进制（UUID 无横线） */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 提取或生成 traceId（外部传入需校验格式，防止日志注入/响应膨胀）
        String traceId = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        if (!StringUtils.hasText(traceId) || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            traceId = IdUtil.uuid32();
        }

        // 2. 存入 exchange attributes（供后续 Filter 和 Error Handler 使用）
        exchange.getAttributes().put(ATTR_TRACE_ID, traceId);

        // 3. 防伪造：先移除外部传入的 X-Trace-Id，再设置为可信值，透传给下游
        final String finalTraceId = traceId;
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(HEADER_TRACE_ID))
                .header(HEADER_TRACE_ID, finalTraceId)
                .build();

        // 4. 响应头也携带 X-Trace-Id（供前端在非 JSON 场景下读取，如文件下载）
        exchange.getResponse().getHeaders().set(HEADER_TRACE_ID, finalTraceId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        // 最高优先级（在 JwtAuthGlobalFilter=-100 之前），确保 traceId 最先就绪
        return -110;
    }
}
