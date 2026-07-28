package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.metrics.IotMetrics;
import io.micrometer.core.instrument.Timer;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * C04 体征信封生产者。
 * <p>
 * Topic: elder-vital-raw，Tag 按设备类型。
 * 异步发送，单条独立消息；失败严格按 10s/30s/60s 调度三次重试。
 * <p>
 * 注意：C04 按当前需求不持久化体征，"生产端失败入死信"与"不持久化体征"存在设计矛盾；
 * 本类不伪造 DLQ，重试三次失败后计数、日志告警并丢弃（方案 A 默认实现）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VitalSignProducer {

    private static final String EVENT_TYPE = "VITAL_SIGN_REPORTED";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {10_000L, 30_000L, 60_000L};
    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService iotRetryScheduler;
    private final IotMetrics metrics;

    private final Map<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    public void send(ParsedVitalSign event) {
        sendInternal(event, 0);
    }

    private void sendInternal(ParsedVitalSign event, int attempt) {
        String destination = MqTopicConstants.VITAL_SIGN_TOPIC + ":" + event.deviceType();
        String json;
        try {
            json = buildMessageJson(event);
        } catch (Exception e) {
            log.error("体征信封序列化失败: eventId={}", event.eventId(), e);
            metrics.mqFailed(MqTopicConstants.VITAL_SIGN_TOPIC, event.deviceType(), "serialization_failure");
            return;
        }

        Message<String> message = MessageBuilder.withPayload(json)
                .setHeader(TRACE_ID_HEADER, event.traceId())
                .build();
        retryCounters.computeIfAbsent(event.eventId(), k -> new AtomicInteger(0));
        Timer.Sample sendTimer = metrics.startTimer();

        rocketMQTemplate.asyncSend(destination, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                withTraceId(event.traceId(), () -> {
                    metrics.recordMqSendLatency(sendTimer, MqTopicConstants.VITAL_SIGN_TOPIC, event.deviceType());
                    retryCounters.remove(event.eventId());
                    metrics.mqSent(MqTopicConstants.VITAL_SIGN_TOPIC, event.deviceType());
                    log.debug("体征发送成功: eventId={}, topic={}", event.eventId(), destination);
                });
            }

            @Override
            public void onException(Throwable e) {
                withTraceId(event.traceId(), () -> {
                    metrics.recordMqSendLatency(sendTimer, MqTopicConstants.VITAL_SIGN_TOPIC, event.deviceType());
                    int currentAttempt = retryCounters.get(event.eventId()).incrementAndGet();
                    log.warn("体征发送失败: eventId={}, attempt={}", event.eventId(), currentAttempt, e);
                    metrics.mqRetried(MqTopicConstants.VITAL_SIGN_TOPIC, event.deviceType(), currentAttempt);
                    if (currentAttempt <= MAX_RETRIES) {
                        long delay = RETRY_DELAYS_MS[currentAttempt - 1];
                        iotRetryScheduler.schedule(() -> sendInternal(event, currentAttempt), delay, TimeUnit.MILLISECONDS);
                    } else {
                        retryCounters.remove(event.eventId());
                        metrics.mqFailed(MqTopicConstants.VITAL_SIGN_TOPIC, event.deviceType(), "send_failure");
                        log.error("体征发送三次重试后仍未成功，按默认策略丢弃: eventId={}", event.eventId());
                    }
                });
            }
        }, 3_000L);
    }

    private static void withTraceId(String traceId, Runnable action) {
        String previousTraceId = TraceContext.currentTraceId();
        try {
            TraceContext.setTraceId(traceId);
            action.run();
        } finally {
            if (previousTraceId == null) {
                TraceContext.clear();
            } else {
                TraceContext.setTraceId(previousTraceId);
            }
        }
    }

    /**
     * 构建 C04 体征信封：rawPayload 先写入，再由平台固定字段覆盖；
     * 固定字段始终存在，elder_id 输出字符串或显式 null。
     */
    private String buildMessageJson(ParsedVitalSign event) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId());
        envelope.put("eventType", EVENT_TYPE);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("occurredAt", formatIso(event.occurredAt()));
        envelope.put("traceId", event.traceId());
        envelope.put("producer", MqTopicConstants.PRODUCER);

        Map<String, Object> payload = new LinkedHashMap<>();
        // ① 先写入厂商 rawPayload
        if (event.rawPayload() != null) {
            payload.putAll(event.rawPayload());
        }
        // ② 平台固定字段覆盖，禁止厂商字段覆盖 sourceMessageId/device_id/elder_id/data_time
        payload.put("sourceMessageId", event.sourceMessageId());
        payload.put("device_id", event.deviceId());
        payload.put("elder_id", event.elderId() != null ? String.valueOf(event.elderId()) : null);
        payload.put("data_time", toEpochMillis(event.occurredAt()));
        // ③ 可选体征字段
        if (event.heartRate() != null) {
            payload.put("heart_rate", event.heartRate());
        }
        if (event.respiratoryRate() != null) {
            payload.put("respiratory_rate", event.respiratoryRate());
        }
        if (event.bodyMovement() != null) {
            payload.put("body_movement", event.bodyMovement());
        }
        if (event.bedStatus() != null) {
            payload.put("bed_status", event.bedStatus());
        }
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }

    private static String formatIso(OffsetDateTime occurredAt) {
        return occurredAt != null ? occurredAt.toInstant().toString() : Instant.now().toString();
    }

    private static long toEpochMillis(OffsetDateTime occurredAt) {
        return occurredAt != null ? occurredAt.toInstant().toEpochMilli() : System.currentTimeMillis();
    }
}
