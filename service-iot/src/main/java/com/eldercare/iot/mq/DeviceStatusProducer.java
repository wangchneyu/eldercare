package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.heartbeat.DeviceStatusEvent;
import com.eldercare.iot.metrics.IotMetrics;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * C09 device-status producer.
 *
 * <p>The C09 contract explicitly freezes the message body to six fields instead of
 * the platform's general event envelope. Delivery is best effort: state transitions
 * are completed locally before this producer is scheduled, and a broker failure must
 * never delay heartbeat detection or turn a non-P0 event into an Outbox workflow.</p>
 */
@Slf4j
@Component
public class DeviceStatusProducer {

    static final String TRACE_ID_HEADER = "X-Trace-Id";
    static final String METRIC_TAG = "STATUS";
    private static final long SEND_TIMEOUT_MS = 3_000L;

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final Executor iotMqExecutor;
    private final IotMetrics metrics;
    private final String topic;

    public DeviceStatusProducer(
            RocketMQTemplate rocketMQTemplate,
            ObjectMapper objectMapper,
            @Qualifier("iotMqExecutor") Executor iotMqExecutor,
            IotMetrics metrics,
            @Value("${iot.device-status.topic:" + MqTopicConstants.DEVICE_STATUS_TOPIC + "}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.iotMqExecutor = iotMqExecutor;
        this.metrics = metrics;
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("iot.device-status.topic must not be blank");
        }
        this.topic = topic;
    }

    /**
     * Schedules a state notification away from the caller, which can be a Disruptor
     * handler or the heartbeat timeout scanner. This method intentionally never
     * propagates a RocketMQ failure to the state machine.
     */
    public void publish(DeviceStatusEvent event) {
        if (event == null) {
            return;
        }
        try {
            iotMqExecutor.execute(() -> withTraceId(event.traceId(), () -> sendAsync(event)));
        } catch (RejectedExecutionException e) {
            recordFailure(event, "executor_rejected", e);
        } catch (RuntimeException e) {
            recordFailure(event, "send_failure", e);
        }
    }

    private void sendAsync(DeviceStatusEvent event) {
        final String messageJson;
        try {
            messageJson = buildMessageJson(event);
        } catch (Exception e) {
            recordFailure(event, "serialization_failure", e);
            return;
        }

        Message<String> message = MessageBuilder.withPayload(messageJson)
                .setHeader(TRACE_ID_HEADER, event.traceId())
                .build();
        Timer.Sample sendTimer = metrics.startTimer();
        try {
            rocketMQTemplate.asyncSend(topic, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    withTraceId(event.traceId(), () -> {
                        metrics.recordMqSendLatency(sendTimer, topic, METRIC_TAG);
                        metrics.mqSent(topic, METRIC_TAG);
                        log.debug("Device-status send succeeded: deviceId={}, oldStatus={}, newStatus={}, topic={}",
                                event.deviceId(), event.oldStatus(), event.newStatus(), topic);
                    });
                }

                @Override
                public void onException(Throwable cause) {
                    withTraceId(event.traceId(), () -> {
                        metrics.recordMqSendLatency(sendTimer, topic, METRIC_TAG);
                        recordFailure(event, "send_failure", cause);
                    });
                }
            }, SEND_TIMEOUT_MS);
        } catch (RuntimeException e) {
            metrics.recordMqSendLatency(sendTimer, topic, METRIC_TAG);
            recordFailure(event, "send_failure", e);
        }
    }

    /** Builds the exact six-field C09 payload in the frozen field order. */
    private String buildMessageJson(DeviceStatusEvent event) throws Exception {
        requireText(event.deviceId(), "deviceId");
        requireText(event.deviceType(), "deviceType");
        requireValue(event.oldStatus(), "oldStatus");
        requireValue(event.newStatus(), "newStatus");
        requireValue(event.occurredAt(), "occurredAt");
        requireText(event.traceId(), "traceId");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", event.deviceId());
        payload.put("deviceType", event.deviceType());
        payload.put("oldStatus", event.oldStatus().getCode());
        payload.put("newStatus", event.newStatus().getCode());
        payload.put("occurredAt", event.occurredAt().toInstant().toString());
        payload.put("traceId", event.traceId());
        return objectMapper.copy()
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .writeValueAsString(payload);
    }

    private void recordFailure(DeviceStatusEvent event, String reason, Throwable cause) {
        metrics.mqFailed(topic, METRIC_TAG, reason);
        log.warn("Device-status send failed: deviceId={}, oldStatus={}, newStatus={}, topic={}, reason={}",
                event.deviceId(), event.oldStatus(), event.newStatus(), topic, reason, cause);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("C09 field %s must not be blank".formatted(field));
        }
    }

    private static void requireValue(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("C09 field %s must not be null".formatted(field));
        }
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
