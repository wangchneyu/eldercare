package com.eldercare.edge.support;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.mqtt.ManagedMqttClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用“假”MQTT 客户端：始终在线、捕获发布内容，用于验证
 * 通知发布/补传的原字节与 Topic，代替真实 Broker。
 */
public final class FakeMqttClient extends ManagedMqttClient {

    public record Published(String topic, byte[] payload, int qos) {
    }

    private final CopyOnWriteArrayList<Published> published = new CopyOnWriteArrayList<>();
    private volatile boolean failNextPublish;

    public FakeMqttClient(String role) {
        super(role, config(), new String[0], new int[0], (t, p, q, i) -> {
        });
    }

    private static EdgeProperties.Mqtt config() {
        EdgeProperties.Mqtt c = new EdgeProperties.Mqtt();
        c.setAutoConnect(false);
        return c;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void publish(String topic, byte[] payload, int qos) throws org.eclipse.paho.client.mqttv3.MqttException {
        if (failNextPublish) {
            failNextPublish = false;
            throw new org.eclipse.paho.client.mqttv3.MqttException(
                    org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        published.add(new Published(topic, payload, qos));
    }

    public void setFailNextPublish(boolean failNextPublish) {
        this.failNextPublish = failNextPublish;
    }

    @Override
    public void ack(int pahoMessageId, int qos) {
        // 测试中不连接真实 Broker，无需 ACK
    }

    public List<Published> published() {
        return published;
    }
}