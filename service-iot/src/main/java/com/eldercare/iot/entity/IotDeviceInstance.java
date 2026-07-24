package com.eldercare.iot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 设备实例表 — iot_device_instance
 */
@Data
@TableName("iot_device_instance")
public class IotDeviceInstance implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private String deviceId;

    private String serialNo;

    private String deviceName;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long modelId;

    private String mqttClientId;

    private String lifecycleStatus;

    private String onlineStatus;

    private OffsetDateTime lastHeartbeatAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
