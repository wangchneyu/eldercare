package com.eldercare.iot;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * 集成测试公共配置：为外部依赖提供占位 Bean，避免 Spring 上下文因缺少真实 RocketMQ 等基础设施而失败。
 * <p>
 * 注意：本配置仅在测试类引入 {@code @Import(IntegrationTestConfig.class)} 时生效，
 * 不会污染生产代码。
 */
@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    @Primary
    public RocketMQTemplate rocketMQTemplate() {
        return mock(RocketMQTemplate.class);
    }
}
