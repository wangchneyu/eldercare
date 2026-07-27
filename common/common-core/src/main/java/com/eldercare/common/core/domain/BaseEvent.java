package com.eldercare.common.core.domain;

import com.eldercare.common.core.utils.IdUtil;
import com.eldercare.common.core.utils.TraceContext;
import lombok.Data;

import java.io.Serializable;
import java.time.ZonedDateTime;

/**
 * MQ 事件基类 — 所有业务事件必须继承此类
 * <p>
 * 提供事件信封字段（envelope）的自动填充，确保全链路可追踪。
 * 子类通过 {@link #of(Object, String, String)} 工厂方法构建事件实例。
 *
 * <h3>使用示例:</h3>
 * <pre>{@code
 * DeviceDataEvent event = BaseEvent.of(payload, "iot_device_data_v1", "service-iot");
 * rocketMQTemplate.convertAndSend("iot_device_data_v1", event);
 * }</pre>
 *
 * @param <T> 业务载荷类型，严禁包含敏感字段（Token、密码、证件号等）
 */
@Data
public abstract class BaseEvent<T> implements Serializable {

    /** 全局唯一事件 ID（32 位 UUID，无连字符） */
    private String eventId;

    /** 事件类型/主题，格式: {domain}_{event_type}_{version}，如 iot_device_data_v1 */
    private String eventType;

    /** 事件 Schema 版本号，用于消费者反序列化兼容校验 */
    private String schemaVersion;

    /** UTC ISO-8601 事件产生时间 */
    private ZonedDateTime occurredAt;

    /** 贯穿全链路的 traceId */
    private String traceId;

    /** 生产者标识：服务名及版本，如 service-iot:1.0.0 */
    private String producer;

    /** 业务载荷（严禁敏感字段） */
    private T payload;

    /**
     * 创建事件并自动填充 envelope 字段
     *
     * @param payload     业务载荷
     * @param eventType   事件类型（Topic 名）
     * @param producer    生产者标识
     * @param <P>         载荷类型
     * @param <E>         事件类型
     * @return 填充了所有 envelope 字段的事件实例
     */
    public static <P, E extends BaseEvent<P>> E of(P payload, String eventType, String producer) {
        try {
            @SuppressWarnings("unchecked")
            E event = (E) payload.getClass().getEnclosingClass()
                    .getDeclaredConstructor().newInstance();
            fillEnvelope(event, eventType, producer);
            event.setPayload(payload);
            return event;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法实例化事件子类，请确认事件类为 BaseEvent 的直接 static 内部类", e);
        }
    }

    /**
     * 直接设置载荷（不使用工厂方法时的便捷方式）
     */
    public void fillEnvelope(String eventType, String producer) {
        fillEnvelope(this, eventType, producer);
    }

    /**
     * 填充所有 envelope 字段
     */
    private static void fillEnvelope(BaseEvent<?> event, String eventType, String producer) {
        event.setEventId(IdUtil.uuid32());
        event.setEventType(eventType);
        event.setSchemaVersion("1.0");
        event.setOccurredAt(ZonedDateTime.now());
        event.setTraceId(TraceContext.currentTraceId());
        event.setProducer(producer);
    }
}
