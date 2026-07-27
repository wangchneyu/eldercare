package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import com.eldercare.iot.mqtt.InboundMqttMessage;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P0 事件发件箱：本地事务写入原始 C05 信封与 Outbox 记录，依靠 PostgreSQL ON CONFLICT 去重。
 * <p>
 * 事务提交后由 {@link SosEventProducer} 同步发送 MQ；发送失败保持 PENDING 等待补发任务。
 * MQTT QoS1 消息在 Outbox 事务提交后手动 ack，确保未 ack 消息可由 broker 按持久会话重发。
 */
@Slf4j
@Service
public class OutboxService {

    private final IotMqOutboxMapper outboxMapper;
    private final SosEventProducer sosEventProducer;
    private final PlatformTransactionManager transactionManager;
    private final IotMetrics metrics;

    public OutboxService(IotMqOutboxMapper outboxMapper,
                         @Lazy SosEventProducer sosEventProducer,
                         PlatformTransactionManager transactionManager,
                         IotMetrics metrics) {
        this.outboxMapper = outboxMapper;
        this.sosEventProducer = sosEventProducer;
        this.transactionManager = transactionManager;
        this.metrics = metrics;
    }

    /**
     * SOS/FALL 事件入口：写 Outbox → 事务提交后发送 MQ → ack MQTT。
     */
    public void handleSosEvent(ParsedSosEvent event, InboundMqttMessage inbound) {
        String existingTraceId = TraceContext.currentTraceId();
        try {
            if (existingTraceId == null || existingTraceId.isEmpty()) {
                TraceContext.setTraceId(event.traceId());
            }
            IotMqOutbox outbox = buildOutbox(event);
            boolean saved = saveInTransaction(outbox);
            if (!saved) {
                log.warn("SOS/FALL 重复消息已忽略: deviceId={}, sourceMessageId={}, eventType={}",
                        event.deviceId(), event.sourceMessageId(), event.eventType());
                metrics.outboxDuplicate(event.eventType());
                return;
            }

            // 事务已提交：消息进入可恢复链路，可以 ack MQTT
            if (inbound != null && inbound.requiresAck()) {
                inbound.ack();
                metrics.mqttMessageAcked(String.valueOf(inbound.qos()));
            }

            log.info("P0 Outbox 已写入: eventId={}, eventType={}", outbox.getEventId(), outbox.getEventType());
            sosEventProducer.send(outbox);
        } finally {
            if (existingTraceId == null || existingTraceId.isEmpty()) {
                TraceContext.clear();
            }
        }
    }

    /**
     * 重载：不带 MQTT 凭证（仅用于测试或内部调用）。
     */
    public void handleSosEvent(ParsedSosEvent event) {
        handleSosEvent(event, null);
    }

    private boolean saveInTransaction(IotMqOutbox outbox) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer rows = tx.execute(status -> outboxMapper.insertOnConflict(outbox));
        boolean inserted = rows != null && rows > 0;
        if (inserted) {
            metrics.outboxSaved(outbox.getEventType(), outbox.getStatus());
        }
        return inserted;
    }

    /**
     * 标记 SENT：条件更新，仅在当前为 PENDING 时生效，防止并发下被失败路径覆盖。
     */
    public boolean markSent(String eventId) {
        int rows = outboxMapper.updateStatusConditionally(
                eventId,
                OutboxStatus.SENT.getCode(),
                OutboxStatus.PENDING.getCode(),
                OffsetDateTime.now()
        );
        if (rows == 0) {
            metrics.outboxStatusConflict(eventId, OutboxStatus.PENDING.getCode(), "unknown");
            return false;
        }
        return true;
    }

    /**
     * 记录可恢复失败：条件更新，仅在当前为 PENDING 时递增 retry_count 并保留 PENDING。
     */
    public boolean recordFailure(String eventId, int retryCount, String error) {
        int rows = outboxMapper.updateFailureConditionally(
                eventId,
                OutboxStatus.PENDING.getCode(),
                OutboxStatus.PENDING.getCode(),
                retryCount,
                error
        );
        if (rows == 0) {
            metrics.outboxStatusConflict(eventId, OutboxStatus.PENDING.getCode(), "unknown");
            return false;
        }
        return true;
    }

    /**
     * 标记不可恢复 FAILED：条件更新，仅在当前为 PENDING 时生效。
     */
    public boolean markFailed(String eventId, String error) {
        int rows = outboxMapper.updateFailureConditionally(
                eventId,
                OutboxStatus.FAILED.getCode(),
                OutboxStatus.PENDING.getCode(),
                0,
                error
        );
        return rows > 0;
    }

    private IotMqOutbox buildOutbox(ParsedSosEvent event) {
        String traceId = TraceContext.currentTraceId();
        if (traceId == null || traceId.isEmpty()) {
            traceId = event.traceId();
        }

        Map<String, Object> payload = buildPayload(event);
        Map<String, Object> rawEnvelope = buildRawEnvelope(event, payload, traceId);

        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setId(IdWorker.getId());
        outbox.setEventId(event.eventId());
        outbox.setDeviceId(event.deviceId());
        outbox.setSourceMessageId(event.sourceMessageId());
        outbox.setEventType(event.eventType());
        outbox.setTopic(MqTopicConstants.SOS_EVENT_TOPIC);
        outbox.setTag(resolveTag(event.eventType()));
        outbox.setPayload(payload);
        outbox.setRawEnvelope(rawEnvelope);
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(0);
        outbox.setCreatedAt(OffsetDateTime.now());
        return outbox;
    }

    private Map<String, Object> buildPayload(ParsedSosEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceMessageId", event.sourceMessageId());
        payload.put("deviceId", event.deviceId());
        payload.put("deviceType", event.deviceType());
        payload.put("elderId", event.elderId());
        payload.put("bindingId", event.bindingId());
        payload.put("parkId", event.parkId());
        payload.put("buildingId", event.buildingId());
        payload.put("roomId", event.roomId());
        payload.put("roomNo", event.roomNo());
        payload.put("locationBindingId", event.locationBindingId());
        payload.put("location", event.location());
        payload.put("triggerType", event.triggerType());
        payload.put("batteryLevel", event.batteryLevel());
        // C05 元数据字段（occurredAt/traceId）只在信封顶层，不在 payload 中冗余
        return payload;
    }

    private Map<String, Object> buildRawEnvelope(ParsedSosEvent event, Map<String, Object> payload, String traceId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId());
        envelope.put("eventType", event.eventType());
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", event.occurredAt() != null ? event.occurredAt().toInstant().toString() : null);
        envelope.put("traceId", traceId);
        envelope.put("producer", MqTopicConstants.PRODUCER);
        envelope.put("payload", payload);
        return envelope;
    }

    private String resolveTag(String eventType) {
        return switch (eventType) {
            case "SOS_TRIGGERED" -> MqTopicConstants.TAG_SOS;
            case "FALL_DETECTED" -> MqTopicConstants.TAG_FALL;
            default -> throw new IllegalArgumentException("未知 C05 eventType: " + eventType);
        };
    }
}
