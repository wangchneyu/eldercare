package com.eldercare.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Sentinel 网关限流配置
 * <p>
 * 为 5 条 HTTP 路由定义流控规则（QPS 上限），WebSocket 路由由 WebSocketConnectionLimitFilter 独立管控。
 * <p>
 * 规则在 @PostConstruct 中硬编码加载 — 不依赖 Sentinel Dashboard，确保 Dashboard 不可用时限流仍生效。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SentinelGatewayConfig {

    private final ObjectMapper objectMapper;

    /**
     * 自定义限流异常处理器 — 返回统一 JSON 格式:
     * {@code {"code":100005, "msg":"请求过于频繁，请稍后再试"}}
     * <p>
     * 使用 @Order(-2) 注册 WebExceptionHandler，在 Spring 默认异常处理之前拦截
     */
    @Bean
    @Order(-2)
    public WebExceptionHandler sentinelBlockExceptionHandler() {
        return (ServerWebExchange exchange, Throwable ex) -> {
            // 只处理 Sentinel BlockException，其他异常交给 Spring 默认错误处理器
            if (!(ex instanceof BlockException)) {
                return Mono.error(ex);
            }
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(ex);
            }

            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

            R<Void> result = R.fail(SystemErrorCode.TOO_MANY_REQUESTS);
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(result);
                return exchange.getResponse()
                        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            } catch (Exception e) {
                return Mono.error(e);
            }
        };
    }

    /**
     * 应用启动时加载硬编码的流控规则
     */
    @PostConstruct
    public void initFlowRules() {
        initCustomizedApis();
        initGatewayFlowRules();
        log.info("Sentinel 网关流控规则初始化完成: 5条HTTP路由, QPS上限=100");
    }

    /**
     * 注册 API 分组（按路由路径前缀，与 application.yml 中路由定义一致）
     */
    private void initCustomizedApis() {
        Set<ApiDefinition> definitions = new HashSet<>();

        definitions.add(buildApiDefinition("route-auth", "/auth/**"));
        definitions.add(buildApiDefinition("route-admin", "/admin/**"));
        definitions.add(buildApiDefinition("route-server", "/server/**"));
        definitions.add(buildApiDefinition("route-elderly", "/elderly/**"));
        definitions.add(buildApiDefinition("route-family", "/family/**"));

        GatewayApiDefinitionManager.loadApiDefinitions(definitions);
    }

    /**
     * 构建 API 分组定义（前缀匹配模式）
     */
    private ApiDefinition buildApiDefinition(String name, String pattern) {
        return new ApiDefinition(name)
                .setPredicateItems(Collections.singleton(
                        new ApiPathPredicateItem()
                                .setPattern(pattern)
                                .setMatchStrategy(1))); // 1 = PREFIX 前缀匹配
    }

    /**
     * 定义网关流控规则 — 每条路由 QPS 上限 100
     */
    private void initGatewayFlowRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        String[] apiNames = {"route-auth", "route-admin", "route-server", "route-elderly", "route-family"};
        for (String apiName : apiNames) {
            GatewayFlowRule rule = new GatewayFlowRule(apiName);
            rule.setCount(100);   // QPS 上限
            rule.setIntervalSec(1);
            rules.add(rule);
        }

        GatewayRuleManager.loadRules(rules);
    }
}
