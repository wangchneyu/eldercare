package com.eldercare.iot.mqtt;

import java.util.function.Consumer;

/**
 * MQTT 入站消息包装，携带 Paho 元数据与手动确认回调。
 * <p>
 * 当 {@link MqttConnectionManager} 启用 manual acks 时，必须在消息可靠进入可恢复链路后
 * 调用 {@link #ack()}；否则 broker 会在会话内重发该 QoS1/2 消息。
 */
public record InboundMqttMessage(
        String topic,
        byte[] payload,
        int messageId,
        int qos,
        Consumer<InboundMqttMessage> ackCallback
) {

    /**
     * 执行 MQTT 手动确认。仅在 qos >= 1 且连接仍存活时有效。
     */
    public void ack() {
        if (ackCallback != null && qos >= 1) {
            ackCallback.accept(this);
        }
    }

    public boolean requiresAck() {
        return qos >= 1;
    }
}
