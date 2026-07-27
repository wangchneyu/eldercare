package com.eldercare.iot.parser.model;

import com.eldercare.iot.mqtt.InboundMqttMessage;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * MQTT 入站原始消息，携带 Paho 元数据与手动确认凭证。
 * <p>
 * P0 可靠性：{@link #inboundMqttMessage()} 在消息可靠进入可恢复链路后用于手动 ack。
 */
public record RawDeviceMessage(
    String topic,
    JsonNode envelope,
    String parkId,
    String deviceType,
    String deviceId,
    String messageType,
    String eventId,
    String traceId,
    OffsetDateTime occurredAt,
    int mqttMessageId,
    int mqttQos,
    InboundMqttMessage inboundMqttMessage
) {

    public RawDeviceMessage {
        if (inboundMqttMessage == null) {
            // 允许测试/内部构造时不携带 MQTT 凭证，但生产路径必须传入
            inboundMqttMessage = new InboundMqttMessage(topic, null, mqttMessageId, mqttQos, null);
        }
    }

    /**
     * 简化构造：不携带 MQTT 确认凭证（仅用于测试或内部重建）。
     */
    public RawDeviceMessage(String topic, JsonNode envelope, String parkId, String deviceType,
                            String deviceId, String messageType, String eventId, String traceId,
                            OffsetDateTime occurredAt) {
        this(topic, envelope, parkId, deviceType, deviceId, messageType, eventId, traceId,
                occurredAt, 0, 0, null);
    }

    /**
     * 手动确认 MQTT 消息。应在消息可靠进入可恢复链路后调用。
     */
    public void ackMqtt() {
        if (inboundMqttMessage != null) {
            inboundMqttMessage.ack();
        }
    }

    public boolean requiresAck() {
        return inboundMqttMessage != null && inboundMqttMessage.requiresAck();
    }
}
