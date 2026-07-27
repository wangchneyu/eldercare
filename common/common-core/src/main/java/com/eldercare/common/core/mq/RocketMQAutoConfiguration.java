package com.eldercare.common.core.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 自动配置
 * <p>
 * 根据 {@code eldercare.rocketmq.name-server} 配置自动创建 MQProducer Bean。
 * 未配置时跳过注册，不影响应用启动。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "eldercare.rocketmq", name = "name-server")
@EnableConfigurationProperties(RocketMQProperties.class)
public class RocketMQAutoConfiguration {

    @Bean
    public DefaultMQProducer defaultMQProducer(RocketMQProperties properties) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setRetryTimesWhenSendFailed(2);
        producer.setRetryTimesWhenSendAsyncFailed(2);
        producer.start();
        log.info("RocketMQ Producer 启动成功: nameServer={}, group={}", properties.getNameServer(), properties.getProducerGroup());
        return producer;
    }

    @Bean
    public MQProducer mqProducer(DefaultMQProducer defaultMQProducer, ObjectMapper objectMapper) {
        return new MQProducer(defaultMQProducer, objectMapper);
    }
}
