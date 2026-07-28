package com.eldercare.iot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CareFeignTimeoutConfigTest {

    @Test
    void serviceCareTimeoutBudgetIsBoundBelow500Millis() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test-feign-timeouts",
                    Map.of(
                            "spring.cloud.openfeign.client.config.service-care.connect-timeout", "200",
                            "spring.cloud.openfeign.client.config.service-care.read-timeout", "250")));
            context.register(TestConfig.class);
            context.refresh();

            FeignClientProperties.FeignClientConfiguration config = context
                    .getBean(FeignClientProperties.class)
                    .getConfig()
                    .get("service-care");

            assertEquals(200, config.getConnectTimeout());
            assertEquals(250, config.getReadTimeout());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FeignClientProperties.class)
    static class TestConfig {
    }
}
