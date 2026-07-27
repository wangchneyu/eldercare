package com.eldercare.iot.mq;

import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
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
 * 异步发送，单条独立消息；失败由调度器重试 3 次（10s/30s/60s）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VitalSignProducer {

    private static final String EVENT_TYPE = "VITAL_SIGN_REPORTED";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {10_000L, 30_000L, 60_000L};

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService iotRetryScheduler;

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
            return;
        }

        Message<String> message = MessageBuilder.withPayload(json).build();
        retryCounters.computeIfAbsent(event.eventId(), k -> new AtomicInteger(0));

        rocketMQTemplate.asyncSend(destination, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                retryCounters.remove(event.eventId());
                log.debug("体征发送成功: eventId={}, topic={}", event.eventId(), destination);
            }

            @Override
            public void onException(Throwable e) {
                int currentAttempt = retryCounters.getOrDefault(event.eventId(), new AtomicInteger(0)).incrementAndGet();
                log.warn("体征发送失败: eventId={}, attempt={}", event.eventId(), currentAttempt, e);
                if (currentAttempt < MAX_RETRIES) {
                    long delay = RETRY_DELAYS_MS[currentAttempt - 1];
                    iotRetryScheduler.schedule(() -> sendInternal(event, currentAttempt), delay, TimeUnit.MILLISECONDS);
                } else {
                    retryCounters.remove(event.eventId());
                    log.error("体征发送最终失败，已入死信通道: eventId={}", event.eventId());
                }
            }
        }, 3_000L);
    }

    private String buildMessageJson(ParsedVitalSign event) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId());
        envelope.put("eventType", EVENT_TYPE);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("occurredAt", formatIso(event.occurredAt()));
        envelope.put("traceId", event.traceId());
        envelope.put("producer", MqTopicConstants.PRODUCER);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceMessageId", event.sourceMessageId());
        payload.put("device_id", event.deviceId());
        if (event.elderId() != null) {
            payload.put("elder_id", String.valueOf(event.elderId()));
        }
        payload.put("data_time", toEpochMillis(event.occurredAt()));
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
        if (event.rawPayload() != null) {
            payload.putAll(event.rawPayload());
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
