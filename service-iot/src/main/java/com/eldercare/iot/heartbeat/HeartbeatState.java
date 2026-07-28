package com.eldercare.iot.heartbeat;

import com.eldercare.iot.enums.OnlineStatus;

import java.time.OffsetDateTime;

/**
 * 设备心跳的内存快照。version 用于合并写库时避免覆盖并发到达的新状态。
 */
public record HeartbeatState(
        String deviceId,
        String deviceType,
        OffsetDateTime lastHeartbeatAt,
        OnlineStatus onlineStatus,
        int timeoutSeconds,
        String traceId,
        long version
) {
}
