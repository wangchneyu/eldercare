package com.eldercare.edge;

import com.eldercare.edge.config.EdgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P1-06 Edge Relay 孤岛自治 — 独立 Spring Boot 部署单元。
 * <p>
 * 只依赖本地 SQLite、Paho MQTT、配置与 Actuator/Prometheus；不注册 Nacos，
 * 不连接 PostgreSQL、Redis、RocketMQ、Gateway 或 alert-service，不依赖
 * {@code service-iot} 的任何 Spring Bean，不生成 C04/C05 eventId。
 */
@SpringBootApplication(scanBasePackages = "com.eldercare.edge")
@EnableConfigurationProperties(EdgeProperties.class)
@EnableScheduling
public class EdgeRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeRelayApplication.class, args);
    }
}
