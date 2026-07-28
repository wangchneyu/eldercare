package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.metrics.IotMetrics;
import io.micrometer.core.instrument.Timer;
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
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * C05 SOS/FALL 事件生产者。
 * <p>
 * Topic: elder-sos-event，Tag: SOS / FALL。
 * 同步发送 + 前台 3 次重试；成功更新 Outbox 为 SENT，可恢复失败保持 PENDING 并告警。
 * 首发与补发均直接序列化 Outbox 中持久化的 {@code rawEnvelope}，保证字节级一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosEventProducer {

    static final int SCHEMA_VERSION = 1;
    static final int MAX_FRONT_RETRIES = 3;
    static final long SEND_TIMEOUT_MS = 3_000L;
    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RocketMQTemplate rocketMQTemplate;
    private final OutboxService outboxService;
    private final IotMetrics metrics;

    /**
     * 发送 Outbox 中的 P0 事件（含前台首次发送与补发任务复用）。
     */
    public void send(IotMqOutbox outbox) {
        String destination = outbox.getTopic() + ":" + outbox.getTag();
        String traceId = extractTraceId(outbox);
        String json;
        try {
            json = buildMessageJson(outbox);
        } catch (Exception e) {
            log.error("P0 信封序列化失败: eventId={}", outbox.getEventId(), e);
            outboxService.markFailed(outbox.getEventId(), "信封序列化失败: " + e.getMessage());
            metrics.mqFailed(outbox.getTopic(), outbox.getTag(), "serialization_failure");
            return;
        }

        Message<String> message = MessageBuilder.withPayload(json)
                .setHeader(TRACE_ID_HEADER, traceId)
                .build();

        int baseRetry = outbox.getRetryCount() == null ? 0 : outbox.getRetryCount();
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_FRONT_RETRIES; attempt++) {
            Timer.Sample sendTimer = metrics.startTimer();
            try {
                SendResult sendResult = rocketMQTemplate.syncSend(destination, message, SEND_TIMEOUT_MS);
                if (sendResult != null && SendStatus.SEND_OK == sendResult.getSendStatus()) {
                    boolean marked = outboxService.markSent(outbox.getEventId());
                    if (marked) {
                        metrics.mqSent(outbox.getTopic(), outbox.getTag());
                        recordP0Latency(outbox);
                    }
                    log.info("P0 发送成功: eventId={}, destination={}", outbox.getEventId(), destination);
                    return;
                }
                log.warn("P0 发送未确认: eventId={}, attempt={}, result={}",
                        outbox.getEventId(), attempt, sendResult);
                metrics.mqRetried(outbox.getTopic(), outbox.getTag(), attempt);
            } catch (Exception e) {
                lastException = e;
                log.warn("P0 发送异常: eventId={}, attempt={}", outbox.getEventId(), attempt, e);
                metrics.mqRetried(outbox.getTopic(), outbox.getTag(), attempt);
            } finally {
                metrics.recordMqSendLatency(sendTimer, outbox.getTopic(), outbox.getTag());
            }
        }

        // 可恢复 MQ 失败：保持 PENDING，记录重试与错误，交由 OutboxRetryTask 补发
        String error = lastException != null ? lastException.getMessage() : "MQ 返回非 SEND_OK";
        outboxService.recordFailure(outbox.getEventId(), baseRetry + MAX_FRONT_RETRIES,
                "前台重试 " + MAX_FRONT_RETRIES + " 次失败: " + error);
        metrics.mqFailed(outbox.getTopic(), outbox.getTag(), "send_failure");
        log.error("P0 发送失败进入补偿: eventId={}, retryCount 将递增，保持 PENDING", outbox.getEventId());
    }

    /**
     * 直接从持久化的 rawEnvelope 序列化，首发与补发字节级一致。
     */
    private String buildMessageJson(IotMqOutbox outbox) {
        String rawEnvelopeJson = outbox.getRawEnvelopeJson();
        if (rawEnvelopeJson == null || rawEnvelopeJson.isBlank()) {
            throw new IllegalStateException("Outbox rawEnvelopeJson 为空");
        }
        return rawEnvelopeJson;
    }

    private String extractTraceId(IotMqOutbox outbox) {
        Map<String, Object> rawEnvelope = outbox.getRawEnvelope();
        if (rawEnvelope != null && rawEnvelope.get("traceId") instanceof String traceId) {
            return traceId;
        }
        Map<String, Object> payload = outbox.getPayload();
        if (payload != null && payload.get("traceId") instanceof String traceId) {
            return traceId;
        }
        return outbox.getEventId();
    }

    private void recordP0Latency(IotMqOutbox outbox) {
        Map<String, Object> rawEnvelope = outbox.getRawEnvelope();
        if (rawEnvelope == null) {
            return;
        }
        Object occurredAtObj = rawEnvelope.get("occurredAt");
        if (!(occurredAtObj instanceof String occurredAtStr)) {
            return;
        }
        try {
            Instant occurredAt = OffsetDateTime.parse(occurredAtStr).toInstant();
            long millis = System.currentTimeMillis() - occurredAt.toEpochMilli();
            metrics.recordP0Latency(millis);
        } catch (DateTimeParseException ignored) {
            // 非法时间戳不记录
        }
    }
}
