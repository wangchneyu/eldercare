package com.eldercare.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 网关就绪探针
 * <p>
 * 启动完成前（路由规则未加载、Nacos 未注册成功），/actuator/health 返回 OUT_OF_SERVICE，
 * 防止流量提前进入。ApplicationReadyEvent 触发后变为 UP。
 */
@Slf4j
@Component
public class GatewayReadinessHealthIndicator implements HealthIndicator, ApplicationListener<ApplicationReadyEvent> {

    private volatile boolean ready = false;

    @Override
    public Health health() {
        if (ready) {
            return Health.up().withDetail("gateway", "routes loaded, nacos registered").build();
        }
        return Health.outOfService().withDetail("gateway", "starting up, not ready to accept traffic").build();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ready = true;
        log.info("网关就绪：路由规则已加载，开始接收流量");
    }
}
