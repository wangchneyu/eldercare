package com.eldercare.iot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 设备绑定表 — iot_device_binding
 */
@Data
@TableName("iot_device_binding")
public class IotDeviceBinding implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private String bindingId;

    private String deviceId;

    private String bindingType;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long elderId;

    private String parkId;

    private String buildingId;

    private String roomId;

    private String roomNo;

    private String locationId;

    private String locationType;

    private String locationName;

    private String floorId;

    private String status;

    private OffsetDateTime activeFrom;

    private OffsetDateTime inactiveAt;

    private OffsetDateTime createdAt;
}
