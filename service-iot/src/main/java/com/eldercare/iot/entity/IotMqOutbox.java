package com.eldercare.iot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.eldercare.iot.config.JsonbTypeHandler;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * P0 事件发件箱 — iot_mq_outbox。
 * <p>
 * payload 保存 C05 的业务 payload；rawEnvelopeJson 保存创建事务中的完整原始信封。
 * 首发与补发均直接发送 rawEnvelopeJson，禁止在补发时按当前时间重新拼装。
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

    /** 完整 C05 原始信封 JSON（首发/补发字节级一致） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> rawEnvelope;

    /** Exact C05 JSON produced in the creating transaction and reused by retries. */
    private String rawEnvelopeJson;

    private String status;

    private Integer retryCount;

    private String lastError;

    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;

    private OffsetDateTime nextRetryAt;

    /** 租约过期时间，用于原子 claim/并发控制 */
    private OffsetDateTime leaseExpireAt;

    /** 领取该记录的实例标识 */
    private String claimedBy;
}
