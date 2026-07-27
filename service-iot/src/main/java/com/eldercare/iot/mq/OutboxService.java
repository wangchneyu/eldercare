package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * P0 事件发件箱：本地事务写入原始 C05 信封与 Outbox 记录，依靠数据库唯一约束去重。
 * <p>
 * 事务提交后由 {@link SosEventProducer} 同步发送 MQ；发送失败保持 PENDING 等待补发任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final IotMqOutboxMapper outboxMapper;
    private final SosEventProducer sosEventProducer;
    private final PlatformTransactionManager transactionManager;

    /**
     * SOS/FALL 事件入口：写 Outbox → 事务提交后发送 MQ。
     */
    public void handleSosEvent(ParsedSosEvent event) {
        String existingTraceId = TraceContext.currentTraceId();
        try {
            // 仅在当前线程未设置 traceId 时使用事件携带的 traceId，保留上游调用链
            if (existingTraceId == null || existingTraceId.isEmpty()) {
                TraceContext.setTraceId(event.traceId());
            }
            IotMqOutbox outbox = buildOutbox(event);
            boolean saved = saveInTransaction(outbox);
            if (!saved) {
                log.warn("SOS/FALL 重复消息已忽略: deviceId={}, sourceMessageId={}, eventType={}",
                        event.deviceId(), event.sourceMessageId(), event.eventType());
                return;
            }
            log.info("P0 Outbox 已写入: eventId={}, eventType={}", outbox.getEventId(), outbox.getEventType());
            sosEventProducer.send(outbox);
        } finally {
            if (existingTraceId == null || existingTraceId.isEmpty()) {
                TraceContext.clear();
            }
        }
    }

    private boolean saveInTransaction(IotMqOutbox outbox) {
        // TransactionTemplate 保证 Outbox 写入与数据库唯一约束检查在同一事务中完成。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Boolean result = tx.execute(status -> {
            try {
                outboxMapper.insert(outbox);
                return true;
            } catch (DataIntegrityViolationException e) {
                log.warn("SOS/FALL 消息唯一约束冲突: deviceId={}, sourceMessageId={}, eventType={}",
                        outbox.getDeviceId(), outbox.getSourceMessageId(), outbox.getEventType());
                return false;
            }
        });
        return result != null && result;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markSent(String eventId) {
        IotMqOutbox outbox = outboxMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotMqOutbox>()
                        .eq(IotMqOutbox::getEventId, eventId)
        );
        if (outbox == null) {
            return false;
        }
        outbox.setStatus(OutboxStatus.SENT.getCode());
        outbox.setSentAt(OffsetDateTime.now());
        return outboxMapper.updateById(outbox) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean recordFailure(String eventId, String error) {
        IotMqOutbox outbox = outboxMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotMqOutbox>()
                        .eq(IotMqOutbox::getEventId, eventId)
        );
        if (outbox == null) {
            return false;
        }
        outbox.setRetryCount(outbox.getRetryCount() == null ? 1 : outbox.getRetryCount() + 1);
        outbox.setLastError(error);
        // 可恢复 MQ 故障保持 PENDING，不标记 FAILED
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        return outboxMapper.updateById(outbox) > 0;
    }

    private IotMqOutbox buildOutbox(ParsedSosEvent event) {
        Map<String, Object> payload = new HashMap<>();
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
        // 保存原始 occurredAt，用于补发时重建信封，发送时会提升到顶层
        payload.put("occurredAt", event.occurredAt() != null ? event.occurredAt().toInstant().toString() : null);
        // 保存 traceId，补发任务跨线程复用；优先使用当前线程 TraceContext，其次使用事件携带的 traceId
        String traceId = TraceContext.currentTraceId();
        if (traceId == null || traceId.isEmpty()) {
            traceId = event.traceId();
        }
        payload.put("traceId", traceId);

        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId(event.eventId());
        outbox.setDeviceId(event.deviceId());
        outbox.setSourceMessageId(event.sourceMessageId());
        outbox.setEventType(event.eventType());
        outbox.setTopic(MqTopicConstants.SOS_EVENT_TOPIC);
        outbox.setTag(event.eventType().startsWith("SOS") ? MqTopicConstants.TAG_SOS : MqTopicConstants.TAG_FALL);
        outbox.setPayload(payload);
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(0);
        outbox.setCreatedAt(OffsetDateTime.now());
        return outbox;
    }
}
