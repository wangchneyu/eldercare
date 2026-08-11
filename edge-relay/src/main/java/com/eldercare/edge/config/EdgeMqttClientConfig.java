package com.eldercare.edge.config;

import com.eldercare.edge.mqtt.ManagedMqttClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 两个独立且持久会话的 MQTT 客户端：
 * <ul>
 *   <li>本地客户端：订阅设备上行与护工终端回执（QoS 1 / manual ACK）；</li>
 *   <li>云端客户端：只将原设备上行重放到云端 EMQX（无订阅）。</li>
 * </ul>
 */
@Configuration
public class EdgeMqttClientConfig {

    @Bean(destroyMethod = "destroy")
    public ManagedMqttClient edgeLocalMqttClient(EdgeProperties properties) {
        return ManagedMqttClient.local(properties, (topic, payload, qos, id) -> {
        });
    }

    @Bean(destroyMethod = "destroy")
    public ManagedMqttClient edgeCloudMqttClient(EdgeProperties properties) {
        return ManagedMqttClient.cloud(properties);
    }
}