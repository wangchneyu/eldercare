package com.eldercare.iot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.eldercare.iot.config.JsonbTypeHandler;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * P0 事件发件箱 — iot_mq_outbox
 */
@Data
@TableName(value = "iot_mq_outbox", autoResultMap = true)
public class IotMqOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private String eventId;

    private String deviceId;

    private String sourceMessageId;

    private String eventType;

    private String topic;

    private String tag;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> payload;

    private String status;

    private Integer retryCount;

    private String lastError;

    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;
}
