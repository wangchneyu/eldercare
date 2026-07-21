package com.eldercare.iot.mqtt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttSubscriber {

    private final MqttConfig config;
    private MqttClient client;

    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(config.getBrokerUrl(), config.getClientId(), new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            if (config.getUsername() != null && !config.getUsername().isBlank()) {
                options.setUserName(config.getUsername());
                options.setPassword(config.getPassword().toCharArray());
            }

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT 连接断开: {}", cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    log.info("收到消息 -> topic: {}, payload: {}", topic, new String(message.getPayload()));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            client.connect(options);

            int[] qos = config.getQos();
            for (int i = 0; i < config.getTopics().length; i++) {
                int q = (i < qos.length) ? qos[i] : 1;
                client.subscribe(config.getTopics()[i], q);
                log.info("已订阅 topic: {} (QoS: {})", config.getTopics()[i], q);
            }

            log.info("MQTT 连接成功 -> broker: {}", config.getBrokerUrl());
        } catch (MqttException e) {
            log.error("MQTT 连接失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
                log.info("MQTT 已断开");
            }
        } catch (MqttException e) {
            log.error("MQTT 断开异常: {}", e.getMessage());
        }
    }
}
