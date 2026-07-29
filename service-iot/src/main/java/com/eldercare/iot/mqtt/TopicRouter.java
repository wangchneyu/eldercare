package com.eldercare.iot.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class TopicRouter {

    private static final Set<String> MESSAGE_TYPES = Set.of("telemetry", "event", "heartbeat");

    // Expected: elder/{parkId}/{deviceType}/{deviceId}/up/{messageType}
    // Segments: 0=elder, 1=parkId, 2=deviceType, 3=deviceId, 4=up, 5=messageType

    public Optional<RouteResult> route(String topic) {
        if (topic == null || topic.isBlank()) {
            log.warn("Topic 为空");
            return Optional.empty();
        }
        String[] parts = topic.split("/", -1);
        if (parts.length != 6) {
            log.warn("Topic 格式不合法（期望 6 段）: {}", topic);
            return Optional.empty();
        }
        if (!"elder".equals(parts[0])) {
            log.warn("Topic 前缀不是 elder: {}", topic);
            return Optional.empty();
        }
        if (!"up".equals(parts[4])) {
            log.warn("Topic 方向不是 up: {}", topic);
            return Optional.empty();
        }
        if (parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank() || !MESSAGE_TYPES.contains(parts[5])) {
            log.warn("Topic parameters or message type are invalid: {}", topic);
            return Optional.empty();
        }
        return Optional.of(new RouteResult(
            parts[1],  // parkId
            parts[2],  // deviceType
            parts[3],  // deviceId
            parts[5]   // messageType: telemetry / event / heartbeat
        ));
    }

    // Record for immutable result
    public record RouteResult(String parkId, String deviceType, String deviceId, String messageType) {}
}
