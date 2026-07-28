package com.eldercare.iot.heartbeat;

import com.eldercare.iot.enums.OnlineStatus;

import java.time.OffsetDateTime;

/**
 * 服务内设备状态事件。C09 冻结前仅供状态机、日志和查询复用，不发送 RocketMQ。
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
