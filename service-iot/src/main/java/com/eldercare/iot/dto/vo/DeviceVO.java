package com.eldercare.iot.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class DeviceVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private String deviceId;
    private String serialNo;
    private String deviceName;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long modelId;
    private String modelCode;
    private String deviceType;
    private String mqttClientId;
    private String lifecycleStatus;
    private String onlineStatus;
    private Integer version;
    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime createdAt;
}
