package com.eldercare.iot.dto.vo;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class DeviceStatusVO {
    private String deviceId;
    private String onlineStatus;
    private OffsetDateTime lastHeartbeat;
    private OffsetDateTime snapshotTime;
}
