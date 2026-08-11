package com.eldercare.edge.model;

/**
 * 入站 MQTT 消息的轻量校验结果（校验阶段不执行任何 I/O）。
 */
public record EnvelopeFields(
        String parkId,
        String deviceType,
        String deviceId,
        String messageType,
        String messageId,
        String protocolVersion,
        String occurredAt
) {
}
