package com.eldercare.common.notify.config;

import com.eldercare.common.notify.service.NotifyService;
import com.eldercare.common.notify.service.impl.MockNotifyServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "eldercare.notify", name = "type", havingValue = "mock", matchIfMissing = true)
    public NotifyService mockNotifyService() {
        return new MockNotifyServiceImpl();
    }
}
