package com.eldercare.iot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Central, low-cardinality metrics entry point for iot-service.
 *
 * <p>Outbox gauges are backed by cached atomics. Prometheus scrapes must never
 * execute a blocking database query on the management request thread.</p>
 */
@Slf4j
@Component
public class IotMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger mqttConnectionCount = new AtomicInteger();
    private final AtomicLong outboxPendingCount = new AtomicLong();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();
    private final AtomicLong vitalDeliveryPendingCount = new AtomicLong();
    private final AtomicLong vitalDeliveryOldestAgeSeconds = new AtomicLong();

    public IotMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("iot_mqtt_connection_count", mqttConnectionCount, AtomicInteger::get)
                .description("Active MQTT client connections for this service instance")
                .register(meterRegistry);
        Gauge.builder("iot_outbox_pending_count", outboxPendingCount, AtomicLong::get)
                .description("Cached count of pending Outbox records")
                .register(meterRegistry);
        Gauge.builder("iot_outbox_oldest_age_seconds", outboxOldestAgeSeconds, AtomicLong::get)
                .description("Cached age of the oldest pending Outbox record")
                .register(meterRegistry);
        Gauge.builder("iot_vital_delivery_pending_count", vitalDeliveryPendingCount, AtomicLong::get)
                .description("Cached count of C04 failure-only delivery records")
                .register(meterRegistry);
        Gauge.builder("iot_vital_delivery_oldest_age_seconds", vitalDeliveryOldestAgeSeconds, AtomicLong::get)
                .description("Cached age of the oldest C04 failure-only delivery record")
                .register(meterRegistry);
    }

    public void mqttConnected(boolean reconnect) {
        mqttConnectionCount.set(1);
        counter("iot_mqtt_connect_total", "status", "success").increment();
        if (reconnect) {
            counter("iot_mqtt_reconnect_total").increment();
        }
    }

    public void mqttDisconnected() {
        mqttConnectionCount.set(0);
    }

    public void mqttConnectionFailed() {
        mqttConnectionCount.set(0);
        counter("iot_mqtt_connect_total", "status", "failure").increment();
    }

    public void mqttMessageReceived(String deviceType) {
        counter("iot_mqtt_messages_total", "device_type", tagValue(deviceType), "status", "received").increment();
    }

    public void mqttMessageRejected(String reason) {
        counter("iot_mqtt_messages_total", "status", "rejected", "reason", tagValue(reason)).increment();
    }

    public void mqttMessageAcked(String qos) {
        counter("iot_mqtt_acks_total", "status", "acked", "qos", tagValue(qos)).increment();
    }

    public void mqttMessageAckFailed(String reason) {
        counter("iot_mqtt_acks_total", "status", "failed", "reason", tagValue(reason)).increment();
    }

    public void mqttMessageProcessingFailed(String stage) {
        counter("iot_mqtt_messages_total", "status", "failed", "stage", tagValue(stage)).increment();
    }

    public void mqttMessageParseFailed(String stage) {
        counter("iot_mqtt_parse_failures_total", "stage", tagValue(stage)).increment();
    }

    public void ringBufferRejected() {
        counter("iot_disruptor_rejected_total").increment();
        log.warn("Disruptor RingBuffer is full; rejecting inbound message");
    }

    public void executorRejected(String executorName) {
        counter("iot_executor_rejected_total", "executor", tagValue(executorName)).increment();
        log.warn("Executor rejected task: executor={}", executorName);
    }

    public void mqSent(String topic, String tag) {
        counter("iot_mq_send_total", "topic", tagValue(topic), "tag", tagValue(tag), "status", "success").increment();
    }

    public void mqFailed(String topic, String tag, String reason) {
        counter("iot_mq_send_total", "topic", tagValue(topic), "tag", tagValue(tag),
                "status", "failure", "reason", failureReason(reason)).increment();
    }

    public void mqRetried(String topic, String tag, int attempt) {
        counter("iot_mq_retry_total", "topic", tagValue(topic), "tag", tagValue(tag),
                "attempt", String.valueOf(attempt)).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordMqSendLatency(Timer.Sample sample, String topic, String tag) {
        if (sample != null) {
            sample.stop(timer("iot_mq_send_latency_seconds", "topic", tagValue(topic), "tag", tagValue(tag)));
        }
    }

    public void recordP0Latency(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(p0LatencyTimer());
        }
    }

    public void recordP0Latency(long millis) {
        if (millis >= 0) {
            p0LatencyTimer().record(millis, TimeUnit.MILLISECONDS);
        }
    }

    public void outboxDuplicate(String eventType) {
        counter("iot_outbox_dedup_total", "event_type", tagValue(eventType)).increment();
    }

    public void outboxSaved(String eventType, String status) {
        counter("iot_outbox_saved_total", "event_type", tagValue(eventType), "status", tagValue(status)).increment();
    }

    public void updateOutboxSnapshot(long pendingCount, Long oldestAgeSeconds) {
        outboxPendingCount.set(Math.max(0, pendingCount));
        outboxOldestAgeSeconds.set(Math.max(0, oldestAgeSeconds == null ? 0 : oldestAgeSeconds));
    }

    public void vitalDeliverySaved(String deviceType) {
        counter("iot_vital_delivery_outbox_total", "status", "saved", "device_type", tagValue(deviceType)).increment();
    }

    public void vitalDeliveryDuplicate(String deviceType) {
        counter("iot_vital_delivery_outbox_total", "status", "duplicate", "device_type", tagValue(deviceType)).increment();
    }

    public void vitalDeliveryRetried(String deviceType) {
        counter("iot_vital_delivery_retry_total", "device_type", tagValue(deviceType)).increment();
    }

    public void vitalDeliveryQuarantined(String deviceType) {
        counter("iot_vital_delivery_outbox_total", "status", "quarantined", "device_type", tagValue(deviceType)).increment();
    }

    public void updateVitalDeliverySnapshot(long pendingCount, Long oldestAgeSeconds) {
        vitalDeliveryPendingCount.set(Math.max(0, pendingCount));
        vitalDeliveryOldestAgeSeconds.set(Math.max(0, oldestAgeSeconds == null ? 0 : oldestAgeSeconds));
    }

    public void bindDeviceStatusGauges(Supplier<Number> onlineCount,
                                       Supplier<Number> offlineCount,
                                       Supplier<Number> oldestHeartbeatAgeSeconds) {
        Gauge.builder("iot_device_online_count", onlineCount)
                .description("Devices currently ONLINE in this instance")
                .register(meterRegistry);
        Gauge.builder("iot_device_offline_count", offlineCount)
                .description("Devices currently OFFLINE in this instance")
                .register(meterRegistry);
        Gauge.builder("iot_heartbeat_oldest_age_seconds", oldestHeartbeatAgeSeconds)
                .description("Age of the oldest ONLINE heartbeat in this instance")
                .register(meterRegistry);
    }

    public void outboxStatusConflict(String eventId, String expected, String actual) {
        counter("iot_outbox_status_conflict_total").increment();
        log.warn("Outbox conditional update conflict: eventId={}, expected={}, actual={}", eventId, expected, actual);
    }

    public void outboxLeaseConflict(String eventId) {
        counter("iot_outbox_lease_conflict_total").increment();
        log.debug("Outbox lease conflict: eventId={}", eventId);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(meterRegistry);
    }

    private Timer p0LatencyTimer() {
        return Timer.builder("iot_p0_latency_seconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    private static String tagValue(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String failureReason(String reason) {
        return switch (reason) {
            case "serialization_failure", "send_failure" -> reason;
            default -> "send_exception";
        };
    }
}
