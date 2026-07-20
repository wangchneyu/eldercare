package com.eldercare.common.core.domain;

import lombok.Data;
import java.io.Serializable;
import java.time.ZonedDateTime;

@Data
public abstract class BaseEvent<T> implements Serializable {
    private String eventId;          // 全局唯一事件 ID
    private String eventType;        // 事件名称
    private Integer schemaVersion;   // 消息结构版本
    private ZonedDateTime occurredAt;// UTC ISO-8601 产生时间
    private String traceId;          // 贯穿全链路的 traceId
    private String producer;         // 服务名及版本
    private T payload;               // 业务数据 (严禁敏感字段)
}
