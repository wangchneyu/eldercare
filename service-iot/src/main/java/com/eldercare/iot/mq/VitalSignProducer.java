package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.metrics.IotMetrics;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * C04 vital-sign producer.
 *
 * <p>It sends the frozen C04 envelope asynchronously and retries after
 * 10s/30s/60s. Only exhausted foreground retries create a failure-only
 * delivery record; normal vital-sign traffic is never persisted locally.</p>
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
    private final VitalDeliveryOutboxService vitalDeliveryOutboxService;

    public void send(ParsedVitalSign event) {
        try {
            VitalDeliveryMessage delivery = new VitalDeliveryMessage(
                    event.eventId(),
                    event.sourceMessageId(),
                    event.deviceId(),
                    event.deviceType(),
                    event.traceId(),
                    buildMessageJson(event)
            );
            sendInternal(delivery, 0);
        } catch (Exception e) {
            log.error("Vital-sign envelope serialization failed: eventId={}", event.eventId(), e);
            metrics.mqFailed(MqTopicConstants.VITAL_SIGN_TOPIC, event.deviceType(), "serialization_failure");
        }
    }

    private void sendInternal(VitalDeliveryMessage delivery, int retryAttempt) {
        String destination = MqTopicConstants.VITAL_SIGN_TOPIC + ":" + delivery.deviceType();
        Message<String> message = MessageBuilder.withPayload(delivery.rawEnvelopeJson())
                .setHeader(TRACE_ID_HEADER, delivery.traceId())
                .build();
        Timer.Sample sendTimer = metrics.startTimer();

        try {
            rocketMQTemplate.asyncSend(destination, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    withTraceId(delivery.traceId(), () -> {
                        metrics.recordMqSendLatency(sendTimer, MqTopicConstants.VITAL_SIGN_TOPIC, delivery.deviceType());
                        metrics.mqSent(MqTopicConstants.VITAL_SIGN_TOPIC, delivery.deviceType());
                        log.debug("Vital-sign send succeeded: eventId={}, destination={}", delivery.eventId(), destination);
                    });
                }

                @Override
                public void onException(Throwable cause) {
                    withTraceId(delivery.traceId(), () -> {
                        metrics.recordMqSendLatency(sendTimer, MqTopicConstants.VITAL_SIGN_TOPIC, delivery.deviceType());
                        handleFailure(delivery, retryAttempt, cause);
                    });
                }
            }, 3_000L);
        } catch (Exception cause) {
            withTraceId(delivery.traceId(), () -> {
                metrics.recordMqSendLatency(sendTimer, MqTopicConstants.VITAL_SIGN_TOPIC, delivery.deviceType());
                handleFailure(delivery, retryAttempt, cause);
            });
        }
    }

    private void handleFailure(VitalDeliveryMessage delivery, int retryAttempt, Throwable cause) {
        int currentAttempt = retryAttempt + 1;
        log.warn("Vital-sign send failed: eventId={}, attempt={}", delivery.eventId(), currentAttempt, cause);
        if (retryAttempt < MAX_RETRIES) {
            metrics.mqRetried(MqTopicConstants.VITAL_SIGN_TOPIC, delivery.deviceType(), currentAttempt);
            try {
                iotRetryScheduler.schedule(
                        () -> sendInternal(delivery, retryAttempt + 1),
                        RETRY_DELAYS_MS[retryAttempt],
                        TimeUnit.MILLISECONDS
                );
                return;
            } catch (Exception schedulerFailure) {
                cause = schedulerFailure;
            }
        }

        metrics.mqFailed(MqTopicConstants.VITAL_SIGN_TOPIC, delivery.deviceType(), "send_failure");
        try {
            vitalDeliveryOutboxService.captureFinalFailure(delivery, currentAttempt, cause);
        } catch (Exception persistenceFailure) {
            log.error("C04 failure-only delivery record could not be persisted: eventId={}",
                    delivery.eventId(), persistenceFailure);
        }
    }

    /** Builds the frozen C04 envelope once so foreground and durable retry bytes match. */
    private String buildMessageJson(ParsedVitalSign event) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId());
        envelope.put("eventType", EVENT_TYPE);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("occurredAt", formatIso(event.occurredAt()));
        envelope.put("traceId", event.traceId());
        envelope.put("producer", MqTopicConstants.PRODUCER);

        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.rawPayload() != null) {
            payload.putAll(event.rawPayload());
        }
        payload.put("sourceMessageId", event.sourceMessageId());
        payload.put("device_id", event.deviceId());
        payload.put("elder_id", event.elderId() != null ? String.valueOf(event.elderId()) : null);
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
        envelope.put("payload", payload);
        // elder_id must stay present even when null (frozen C04 contract, payload.elder_id
        // is required). Keep this independent from the application's REST NON_NULL setting.
        return objectMapper.copy()
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .writeValueAsString(envelope);
    }

    private static String formatIso(OffsetDateTime occurredAt) {
        return occurredAt != null ? occurredAt.toInstant().toString() : Instant.now().toString();
    }

    private static long toEpochMillis(OffsetDateTime occurredAt) {
        return occurredAt != null ? occurredAt.toInstant().toEpochMilli() : System.currentTimeMillis();
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
}
