package com.eldercare.iot.parser.model;

import java.time.OffsetDateTime;
import java.util.Map;

public record ParsedSosEvent(
        String eventId,
        String sourceMessageId,
        String deviceId,
        OffsetDateTime occurredAt,
        String traceId,
        String parkId,
        String deviceType,
        // C05 事件类型：SOS_TRIGGERED / FALL_DETECTED
        String eventType,
        // 绑定快照（ELDER 绑定可选）
        Long elderId,
        String bindingId,
        String buildingId,
        String roomId,
        String roomNo,
        // 位置绑定快照（SOS 按钮必填）
        String locationBindingId,
        Map<String, Object> location,
        // 设备上行字段
        String triggerType,
        Integer batteryLevel,
        // 原始 payload 透传
        Map<String, Object> rawPayload
) implements ParsedEvent {

    public ParsedSosEvent withSnapshot(
            Long elderId,
            String bindingId,
            String buildingId,
            String roomId,
            String roomNo,
            String locationBindingId,
            Map<String, Object> location
    ) {
        return new ParsedSosEvent(
                eventId, sourceMessageId, deviceId, occurredAt, traceId,
                parkId, deviceType, eventType,
                elderId, bindingId, buildingId, roomId, roomNo,
                locationBindingId, location,
                triggerType, batteryLevel, rawPayload
        );
    }
}
