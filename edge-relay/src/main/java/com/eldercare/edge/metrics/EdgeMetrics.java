package com.eldercare.edge.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Edge Relay 指标（Micrometer，前缀 edge_）。日志不得输出完整 payload、密码或证书。
 */
@Component
public class EdgeMetrics {

    private final MeterRegistry registry;

    private final AtomicLong cloudConnected = new AtomicLong(0);
    private final AtomicLong localConnected = new AtomicLong(0);
    private final AtomicLong sqliteUsedBytes = new AtomicLong(0);
    private final AtomicLong unreceiptedP0Notifications = new AtomicLong(0);
    private final AtomicLong oldestQueueAgeSeconds = new AtomicLong(0);
    private final AtomicLong p0QueueDepth = new AtomicLong(0);
    private final AtomicLong pendingDepth = new AtomicLong(0);
    private final AtomicLong forwardingDepth = new AtomicLong(0);

    public EdgeMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("edge_cloud_mqtt_connected", cloudConnected, AtomicLong::get)
                .description("云端 MQTT 连接状态 0/1").register(registry);
        Gauge.builder("edge_local_mqtt_connected", localConnected, AtomicLong::get)
                .description("本地 MQTT 连接状态 0/1").register(registry);
        Gauge.builder("edge_sqlite_used_bytes", sqliteUsedBytes, AtomicLong::get)
                .description("SQLite 已用字节（原始 payload + 冲突证据）").register(registry);
        Gauge.builder("edge_p0_unreceipted_notifications", unreceiptedP0Notifications, AtomicLong::get)
                .description("未回执的 P0 本地终端通知数（仅本地展示，不代表云端告警）").register(registry);
        Gauge.builder("edge_queue_oldest_age_seconds", oldestQueueAgeSeconds, AtomicLong::get)
                .description("队列最老消息年龄（未补传）").register(registry);
        Gauge.builder("edge_p0_queue_depth", p0QueueDepth, AtomicLong::get)
                .description("P0 待补传队列深度").register(registry);
        Gauge.builder("edge_queue_pending_depth", pendingDepth, AtomicLong::get)
                .description("PENDING 队列深度").register(registry);
        Gauge.builder("edge_queue_replaying_depth", forwardingDepth, AtomicLong::get)
                .description("REPLAYING 队列深度").register(registry);
    }

    public void localMqttConnected(boolean connected) {
        localConnected.set(connected ? 1 : 0);
    }

    public void cloudMqttConnected(boolean connected) {
        cloudConnected.set(connected ? 1 : 0);
    }

    public void received(String messageType, String siteId) {
        counter("edge_local_messages_received_total", "本地接收设备上行", "messageType", messageType, "siteId", siteId)
                .increment();
    }

    public void rejected(String reason, String siteId) {
        counter("edge_local_messages_rejected_total", "本地拒绝入站", "reason", reason, "siteId", siteId)
                .increment();
    }

    public void acked(String siteId) {
        counter("edge_local_acks_total", "本地 ACK", "siteId", siteId).increment();
    }

    public void notAcked(String reason, String siteId) {
        counter("edge_local_no_ack_total", "本地不 ACK（P0 配额等）", "reason", reason, "siteId", siteId)
                .increment();
    }

    public void sqliteCommitted(String siteId) {
        counter("edge_sqlite_commits_total", "SQLite 事务提交", "siteId", siteId).increment();
    }

    public void sqliteCapacityRejected(String kind, String siteId) {
        counter("edge_sqlite_capacity_rejected_total", "配额拒绝", "kind", kind, "siteId", siteId).increment();
    }

    public void conflict(String siteId) {
        counter("edge_ingress_conflicts_total", "同源 ID 不同 hash 冲突留证", "siteId", siteId).increment();
    }

    public void corrupted(String siteId) {
        counter("edge_sqlite_corrupted_total", "SHA-256 校验异常留证", "siteId", siteId).increment();
    }

    public void notificationCreated(String terminalId, String siteId) {
        counter("edge_notifications_created_total", "本地终端通知创建", "terminalId", terminalId, "siteId", siteId)
                .increment();
    }

    public void notificationResent(String terminalId, String siteId) {
        counter("edge_notifications_resend_total", "未回执通知重投", "terminalId", terminalId, "siteId", siteId)
                .increment();
    }

    public void notificationReceipted(String terminalId, String siteId) {
        counter("edge_notifications_receipted_total", "终端 DISPLAYED 回执（仅本地展示）",
                "terminalId", terminalId, "siteId", siteId).increment();
    }

    public void cloudForwardSuccess(String siteId) {
        counter("edge_cloud_forward_success_total", "云端补传成功（PUBACK）", "siteId", siteId).increment();
    }

    public void cloudForwardFailure(String siteId) {
        counter("edge_cloud_forward_failure_total", "云端补传失败（at-least-once 重试）", "siteId", siteId)
                .increment();
    }

    public void sqliteUsedBytes(long bytes) {
        sqliteUsedBytes.set(bytes);
    }

    public void unreceiptedP0Notifications(long count) {
        unreceiptedP0Notifications.set(count);
    }

    public void queueDepth(long oldestAgeMillis, long p0Depth, long pending, long replaying) {
        oldestQueueAgeSeconds.set(oldestAgeMillis / 1000);
        p0QueueDepth.set(p0Depth);
        pendingDepth.set(pending);
        forwardingDepth.set(replaying);
    }

    private Counter counter(String name, String description, String... tags) {
        Counter existing = registry.find(name).tags(tags).counter();
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = registry.find(name).tags(tags).counter();
            if (existing != null) {
                return existing;
            }
            return Counter.builder(name).description(description).tags(Tags.of(tags)).register(registry);
        }
    }
}
