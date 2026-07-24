package com.eldercare.iot.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class MessageValidator {

    private static final int MAX_PAYLOAD_SIZE = 65536; // 64KB
    private static final Set<String> VALID_MESSAGE_TYPES = Set.of("VITAL_SIGN", "SOS", "HEARTBEAT");
    private static final Set<String> REQUIRED_FIELDS = Set.of("messageId", "deviceId", "messageType", "protocolVersion", "occurredAt");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Validate and parse the message envelope. Returns the parsed JsonNode if valid.
     */
    public Optional<JsonNode> validate(byte[] payload) {
        if (payload == null || payload.length == 0) {
            log.warn("消息 payload 为空");
            return Optional.empty();
        }
        if (payload.length > MAX_PAYLOAD_SIZE) {
            log.warn("消息 payload 超过大小限制: {} > {}", payload.length, MAX_PAYLOAD_SIZE);
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("消息 JSON 解析失败: {}", e.getMessage());
            return Optional.empty();
        }

        // Check required fields
        for (String field : REQUIRED_FIELDS) {
            if (!root.has(field) || root.get(field).isNull() || root.get(field).asText().isBlank()) {
                log.warn("消息缺少必填字段: {}", field);
                return Optional.empty();
            }
        }

        // Check messageType
        String messageType = root.get("messageType").asText();
        if (!VALID_MESSAGE_TYPES.contains(messageType)) {
            log.warn("未知 messageType: {}（期望: {}）", messageType, VALID_MESSAGE_TYPES);
            return Optional.empty();
        }

        return Optional.of(root);
    }
}
