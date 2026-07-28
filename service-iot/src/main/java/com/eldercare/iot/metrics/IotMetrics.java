package com.eldercare.iot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * iot-service 关键指标统一入口。
 * <p>
 * 覆盖：MQTT 处理、RingBuffer/执行器背压、MQ 发送、Outbox 状态、P0 延迟。
 */
@Slf4j
@Component
public class IotMetrics {

    private final MeterRegistry meterRegistry;

    public IotMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ───────────────────────── MQTT / Inbound ─────────────────────────

    public void mqttMessageReceived(String deviceType) {
        counter("iot_mqtt_messages_total", "device_type", deviceType, "status", "received").increment();
    }

    public void mqttMessageRejected(String reason) {
        counter("iot_mqtt_messages_total", "status", "rejected", "reason", reason).increment();
    }

    public void mqttMessageAcked(String qos) {
        counter("iot_mqtt_acks_total", "status", "acked", "qos", qos).increment();
    }

    public void mqttMessageAckFailed(String reason) {
        counter("iot_mqtt_acks_total", "status", "failed", "reason", reason).increment();
    }

    public void mqttMessageProcessingFailed(String stage) {
        counter("iot_mqtt_messages_total", "status", "failed", "stage", stage).increment();
    }

    // ───────────────────────── Disruptor / Executor 背压 ─────────────────────────

    public void ringBufferRejected() {
        counter("iot_disruptor_rejected_total").increment();
        log.warn("Disruptor RingBuffer 已满，消息被拒绝");
    }

    public void executorRejected(String executorName) {
        counter("iot_executor_rejected_total", "executor", executorName).increment();
        log.warn("执行器拒绝任务: executor={}", executorName);
    }

    // ───────────────────────── MQ 发送 ─────────────────────────

    public void mqSent(String topic, String tag) {
        counter("iot_mq_send_total", "topic", topic, "tag", tag, "status", "success").increment();
    }

    public void mqFailed(String topic, String tag, String reason) {
        counter("iot_mq_send_total", "topic", topic, "tag", tag, "status", "failure", "reason", reason).increment();
    }

    public void mqRetried(String topic, String tag, int attempt) {
        counter("iot_mq_retry_total", "topic", topic, "tag", tag, "attempt", String.valueOf(attempt)).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordP0Latency(Timer.Sample sample) {
        sample.stop(timer("iot_p0_latency_seconds"));
    }

    public void recordP0Latency(long millis) {
        if (millis >= 0) {
            timer("iot_p0_latency_seconds").record(millis, TimeUnit.MILLISECONDS);
        }
    }

    // ───────────────────────── Outbox ─────────────────────────

    public void outboxDuplicate(String eventType) {
        counter("iot_outbox_dedup_total", "event_type", eventType).increment();
    }

    public void outboxSaved(String eventType, String status) {
        counter("iot_outbox_saved_total", "event_type", eventType, "status", status).increment();
    }

    public void registerOutboxPendingGauge(String name, Supplier<Number> pendingCount) {
        Gauge.builder("iot_outbox_pending_count", pendingCount)
                .description("Outbox 待发送记录数")
                .register(meterRegistry);
    }

    public void registerOutboxOldestAgeGauge(String name, Supplier<Number> oldestAgeSeconds) {
        Gauge.builder("iot_outbox_oldest_age_seconds", oldestAgeSeconds)
                .description("Outbox 最老 PENDING 记录年龄（秒）")
                .register(meterRegistry);
    }

    public void outboxStatusConflict(String eventId, String expected, String actual) {
        counter("iot_outbox_status_conflict_total").increment();
        log.warn("Outbox 状态条件更新冲突: eventId={}, expected={}, actual={}", eventId, expected, actual);
    }

    public void outboxLeaseConflict(String eventId) {
        counter("iot_outbox_lease_conflict_total").increment();
        log.debug("Outbox 租约冲突，记录已被其他实例领取: eventId={}", eventId);
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(meterRegistry);
    }
}
