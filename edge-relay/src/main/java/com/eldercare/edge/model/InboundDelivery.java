package com.eldercare.edge.model;

import java.nio.charset.StandardCharsets;

/**
 * 一次 MQTT 入站投递（本地设备上行或护工终端回执）。
 * <p>
 * 回调线程只构造该对象并投递到有界执行器；payload 保持原始字节，
 * SQLite 落库与 ACK 全部发生在工作线程。
 */
public record InboundDelivery(
        String topic,
        byte[] payload,
        int qos,
        int pahoMessageId
) {
    public String payloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public int payloadLength() {
        return payload.length;
    }
}
