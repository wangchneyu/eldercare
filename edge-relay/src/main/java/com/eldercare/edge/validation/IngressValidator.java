package com.eldercare.edge.validation;

import com.eldercare.edge.model.EnvelopeFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 入站轻量校验（edge-relay-design.md §3.1）：六段 Topic、Topic 与信封 deviceId 一致、
 * 必填字段非空、Topic 末段与消息类型匹配、原始 payload ≤ 64 KiB。全程无 I/O。
 * <p>
 * 只做协议信封校验；不解析厂商私有协议、不做绑定/位置快照——业务解析仍由云端 service-iot 完成。
 */
@Component
public class IngressValidator {

    private static final Logger log = LoggerFactory.getLogger(IngressValidator.class);

    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    public static final String TOPIC_PREFIX_ELDER = "elder/";
    public static final String TOPIC_PREFIX_EDGE = "edge/";

    private final ObjectMapper objectMapper;

    public IngressValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验设备上行 Topic + 信封。返回信封字段；空表示拒绝（原因已结构化记录）。
     */
    public Optional<EnvelopeFields> validateDeviceUplink(String topic, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            log.warn("edge_ingress_reject topic={} reason={} size={}", topic, "payload_size_invalid", payload == null ? 0 : payload.length);
            return Optional.empty();
        }
        String[] segments = topic.split("/");
        if (segments.length != 6 || !topic.startsWith(TOPIC_PREFIX_ELDER)
                || !"up".equals(segments[4])) {
            log.warn("edge_ingress_reject topic={} reason={}", topic, "topic_segment_invalid");
            return Optional.empty();
        }
        String parkId = segments[1];
        String deviceType = segments[2];
        String deviceId = segments[3];
        String topicMessageType = segments[5];
        if (parkId.isEmpty() || deviceType.isEmpty() || deviceId.isEmpty()) {
            log.warn("edge_ingress_reject topic={} reason={}", topic, "topic_field_empty");
            return Optional.empty();
        }

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("edge_ingress_reject topic={} reason={} deviceId={}", topic, "payload_not_json", deviceId);
            return Optional.empty();
        }
        if (envelope == null || !envelope.isObject()) {
            log.warn("edge_ingress_reject topic={} reason={} deviceId={}", topic, "envelope_not_object", deviceId);
            return Optional.empty();
        }

        String envelopeDeviceId = textOrEmpty(envelope, "deviceId");
        String messageId = textOrEmpty(envelope, "messageId");
        String messageType = textOrEmpty(envelope, "messageType");
        String protocolVersion = textOrEmpty(envelope, "protocolVersion");
        String occurredAt = textOrEmpty(envelope, "occurredAt");

        if (messageId.isEmpty() || envelopeDeviceId.isEmpty() || messageType.isEmpty()
                || protocolVersion.isEmpty() || occurredAt.isEmpty()) {
            log.warn("edge_ingress_reject topic={} reason={} deviceId={}", topic, "envelope_field_missing", envelopeDeviceId);
            return Optional.empty();
        }
        if (!deviceId.equals(envelopeDeviceId)) {
            log.warn("edge_ingress_reject topic={} reason={} topicDeviceId={} envelopeDeviceId={}",
                    topic, "device_id_mismatch", deviceId, envelopeDeviceId);
            return Optional.empty();
        }

        boolean typeMatches = switch (topicMessageType) {
            case "telemetry" -> "VITAL_SIGN".equals(messageType);
            case "heartbeat" -> "HEARTBEAT".equals(messageType);
            case "event" -> "SOS".equals(messageType) || "FALL".equals(messageType);
            default -> {
                log.warn("edge_ingress_reject topic={} reason={} deviceId={}", topic, "topic_suffix_unknown", deviceId);
                yield false;
            }
        };
        if (!typeMatches) {
            log.warn("edge_ingress_reject topic={} reason={} topicSuffix={} messageType={} deviceId={}",
                    topic, "topic_type_mismatch", topicMessageType, messageType, deviceId);
            return Optional.empty();
        }
        if (!"SOS".equals(messageType) && !"FALL".equals(messageType)
                && !"VITAL_SIGN".equals(messageType) && !"HEARTBEAT".equals(messageType)) {
            log.warn("edge_ingress_reject topic={} reason={} messageType={} deviceId={}",
                    topic, "message_type_unknown", messageType, deviceId);
            return Optional.empty();
        }

        return Optional.of(new EnvelopeFields(parkId, deviceType, deviceId, messageType,
                messageId, protocolVersion, occurredAt));
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return "";
        }
        return value.asText().trim();
    }

    /** 供测试与日志使用。 */
    public static String utf8(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
