package com.eldercare.iot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 设备型号表 — iot_device_model
 */
@Data
@TableName("iot_device_model")
public class IotDeviceModel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private String modelCode;

    private String manufacturer;

    private String deviceType;

    private String parserCode;

    private Integer heartbeatTimeoutSeconds;

    private String description;

    private Boolean enabled;

    @Version
    private Integer version;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
