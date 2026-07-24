package com.eldercare.iot.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class DeviceDetailVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private String deviceId;
    private String serialNo;
    private String deviceName;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long modelId;
    private String modelCode;
    private String deviceType;
    private String manufacturer;
    private String mqttClientId;
    private String lifecycleStatus;
    private String onlineStatus;
    private OffsetDateTime lastHeartbeatAt;
    private Integer heartbeatTimeoutSeconds;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<DeviceBindingVO> currentBindings;
}
