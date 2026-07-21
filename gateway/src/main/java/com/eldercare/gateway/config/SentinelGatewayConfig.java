package com.eldercare.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Set;

/**
 * Sentinel 网关限流配置
 * <p>
 * 为 8 条 HTTP 路由定义流控规则（QPS 上限），WebSocket 路由由 WebSocketConnectionLimitFilter 独立管控。
 * <p>
 * 规则来源优先级: Nacos 动态规则 > 硬编码兜底规则。
 * 应用启动时先加载硬编码规则作为兜底，再尝试从 Nacos 拉取动态规则，拉取成功后覆盖。
 * 确保 Nacos 不可用时，限流仍然生效。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SentinelGatewayConfig {

    private final ObjectMapper objectMapper;

    @Value("${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}")
    private String nacosServerAddr;

    @Value("${spring.cloud.nacos.discovery.namespace:}")
    private String nacosNamespace;

    /** Nacos 网关流控规则 DataId */
    private static final String GW_FLOW_DATA_ID = "gateway-sentinel-flow-rules.json";
    /** Nacos 网关流控规则 Group */
    private static final String GW_FLOW_GROUP = "SENTINEL_GROUP";

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
     * 应用启动时加载流控规则（硬编码兜底 + Nacos 动态规则）
     * <p>
     * 加载顺序: 先硬编码 API 分组和规则 → 再尝试注册 Nacos 动态数据源
     * Nacos 可用时动态规则覆盖硬编码；不可用时硬编码规则保持生效
     */
    @PostConstruct
    public void initFlowRules() {
        initCustomizedApis();
        initGatewayFlowRules();
        log.info("Sentinel 网关流控规则（硬编码兜底）初始化完成: 8条HTTP路由, QPS上限=100");

        try {
            initNacosFlowDataSource();
        } catch (Exception e) {
            log.warn("Nacos 动态流控规则初始化失败，使用硬编码兜底规则。原因: {}", e.getMessage());
        }
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
        definitions.add(buildApiDefinition("route-vital", "/vital/**"));
        definitions.add(buildApiDefinition("route-ai", "/ai/**"));
        definitions.add(buildApiDefinition("route-alert", "/alert/**"));

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

        String[] apiNames = {"route-auth", "route-admin", "route-server", "route-elderly",
                "route-family", "route-vital", "route-ai", "route-alert"};
        for (String apiName : apiNames) {
            GatewayFlowRule rule = new GatewayFlowRule(apiName);
            rule.setCount(100);   // QPS 上限
            rule.setIntervalSec(1);
            rules.add(rule);
        }

        GatewayRuleManager.loadRules(rules);
    }

    /**
     * 注册 Nacos 动态网关流控规则数据源
     * <p>
     * Nacos 规则 JSON 格式示例:
     * <pre>{@code
     * [
     *   { "resource": "route-auth", "count": 100.0, "intervalSec": 1 },
     *   { "resource": "route-admin", "count": 50.0, "intervalSec": 1 }
     * ]
     * }</pre>
     * 规则变更后 ~30s 内生效（Nacos 推送 + Sentinel PropertyListener）
     */
    private void initNacosFlowDataSource() {
        String groupId = GW_FLOW_GROUP;
        String dataId = GW_FLOW_DATA_ID;

        NacosDataSource<Set<GatewayFlowRule>> dataSource = new NacosDataSource<>(
                nacosServerAddr, groupId, dataId,
                source -> {
                    try {
                        return objectMapper.readValue(source, new TypeReference<Set<GatewayFlowRule>>() {});
                    } catch (Exception e) {
                        throw new RuntimeException("解析 Nacos 网关流控规则 JSON 失败", e);
                    }
                });

        // 注册到 GatewayRuleManager，监听 Nacos 配置变更后自动更新
        GatewayRuleManager.register2Property(dataSource.getProperty());
        log.info("Nacos 网关流控规则数据源注册成功: serverAddr={}, group={}, dataId={}",
                nacosServerAddr, groupId, dataId);
    }
}
