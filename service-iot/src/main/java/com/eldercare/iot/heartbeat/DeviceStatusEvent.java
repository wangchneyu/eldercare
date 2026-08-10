package com.eldercare.iot.heartbeat;

import com.eldercare.iot.enums.OnlineStatus;

import java.time.OffsetDateTime;

/**
 * 服务内设备状态事件。
 *
 * <p>C09 Producer 只投递其中冻结的六个状态字段；{@code eventType} 仅用于服务内日志和查询，
 * 不属于 {@code elder-device-status} 消息体。</p>
 */
public record DeviceStatusEvent(
        String deviceId,
        String deviceType,
        OnlineStatus oldStatus,
        OnlineStatus newStatus,
        String eventType,
        OffsetDateTime occurredAt,
        String traceId
) {
}
