package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotMqOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C05 SOS/FALL 事件生产者。
 * <p>
 * Topic: elder-sos-event，Tag: SOS / FALL。
 * 同步发送 + 前台 3 次重试；成功更新 Outbox 为 SENT，可恢复失败保持 PENDING 并告警。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosEventProducer {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_FRONT_RETRIES = 3;
    private static final long SEND_TIMEOUT_MS = 3_000L;

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxService outboxService;

    /**
     * 发送 Outbox 中的 P0 事件（含前台首次发送与补发任务复用）。
     */
    public void send(IotMqOutbox outbox) {
        String destination = outbox.getTopic() + ":" + outbox.getTag();
        String json;
        try {
            json = buildMessageJson(outbox);
        } catch (Exception e) {
            log.error("P0 信封序列化失败: eventId={}", outbox.getEventId(), e);
            outboxService.recordFailure(outbox.getEventId(), "信封序列化失败: " + e.getMessage());
            return;
        }

        Message<String> message = MessageBuilder.withPayload(json).build();
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_FRONT_RETRIES; attempt++) {
            try {
                SendResult sendResult = rocketMQTemplate.syncSend(destination, message, SEND_TIMEOUT_MS);
                if (sendResult != null && SendStatus.SEND_OK == sendResult.getSendStatus()) {
                    outboxService.markSent(outbox.getEventId());
                    log.info("P0 发送成功: eventId={}, destination={}", outbox.getEventId(), destination);
                    return;
                }
                log.warn("P0 发送未确认: eventId={}, attempt={}, result={}",
                        outbox.getEventId(), attempt, sendResult);
            } catch (Exception e) {
                lastException = e;
                log.warn("P0 发送异常: eventId={}, attempt={}", outbox.getEventId(), attempt, e);
            }
        }

        // 可恢复 MQ 失败：保持 PENDING，记录重试与错误，触发告警，交由 OutboxRetryTask 补发
        String error = lastException != null ? lastException.getMessage() : "MQ 返回非 SEND_OK";
        outboxService.recordFailure(outbox.getEventId(),
                "前台重试 " + MAX_FRONT_RETRIES + " 次失败: " + error);
        log.error("P0 发送失败进入补偿: eventId={}, retryCount 将递增，保持 PENDING", outbox.getEventId());
    }

    private String buildMessageJson(IotMqOutbox outbox) throws Exception {
        Map<String, Object> stored = outbox.getPayload();
        if (stored == null) {
            throw new IllegalStateException("Outbox payload 为空");
        }

        Map<String, Object> payload = new LinkedHashMap<>(stored);
        Object occurredAtObj = payload.remove("occurredAt");
        Object traceIdObj = payload.remove("traceId");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", outbox.getEventId());
        envelope.put("eventType", outbox.getEventType());
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("occurredAt", occurredAtObj != null ? occurredAtObj : Instant.now().toString());
        envelope.put("traceId", traceIdObj != null ? traceIdObj : "");
        envelope.put("producer", MqTopicConstants.PRODUCER);
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }
}
