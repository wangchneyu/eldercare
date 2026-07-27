package com.eldercare.gateway;

import com.eldercare.common.security.config.SecurityAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import reactor.core.publisher.Hooks;

@SpringBootApplication(exclude = SecurityAutoConfiguration.class)
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        // 启用 Reactor Context 自动传播，确保 traceId 在 WebFlux 响应式链路中不丢失
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(GatewayApplication.class, args);
        System.out.println("====== Gateway (系统网关) 启动成功 ======");
    }
}