package com.eldercare.iot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * Failure-only delivery record for C04. Normal vital-sign traffic never writes
 * this table; the frozen original envelope is persisted only after foreground
 * RocketMQ retries fail.
 */
@Data
@TableName("iot_vital_delivery_outbox")
public class IotVitalDeliveryOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private String eventId;
    private String deviceId;
    private String sourceMessageId;
    private String deviceType;
    private String topic;
    private String tag;
    private String traceId;
    private String rawEnvelopeJson;
    private String status;
    private Integer retryCount;
    private String lastError;
    private OffsetDateTime createdAt;
    private OffsetDateTime nextRetryAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime sentAt;
    private OffsetDateTime quarantinedAt;
    private OffsetDateTime leaseExpireAt;
    private String claimedBy;
}
