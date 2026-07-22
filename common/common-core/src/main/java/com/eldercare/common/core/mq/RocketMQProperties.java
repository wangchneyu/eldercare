package com.eldercare.common.core.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 配置属性
 */
@Data
@ConfigurationProperties(prefix = "eldercare.rocketmq")
public class RocketMQProperties {

    /** RocketMQ NameServer 地址 */
    private String nameServer;

    /** 生产者分组 */
    private String producerGroup = "eldercare-producer-group";
}
