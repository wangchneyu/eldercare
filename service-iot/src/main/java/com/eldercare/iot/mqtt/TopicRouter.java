package com.eldercare.iot.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Slf4j
@Component
public class TopicRouter {

    // Expected: elder/{parkId}/{deviceType}/{deviceId}/up/{messageType}
    // Segments: 0=elder, 1=parkId, 2=deviceType, 3=deviceId, 4=up, 5=messageType

    public Optional<RouteResult> route(String topic) {
        if (topic == null || topic.isBlank()) {
            log.warn("Topic 为空");
            return Optional.empty();
        }
        String[] parts = topic.split("/");
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
