package com.eldercare.edge.ingress;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.enums.IngressStatus;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.EnvelopeFields;
import com.eldercare.edge.model.InboundDelivery;
import com.eldercare.edge.model.IngressRecord;
import com.eldercare.edge.notify.TerminalNotifier;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.eldercare.edge.storage.QuotaGuard;
import com.eldercare.edge.util.Sha256Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 入站持久化编排：校验 → 配额 → SQLite 事务落库 → （P0）本地通知 → 提交后 ACK。
 * <p>
 * 全部在 edge-ingress-* 工作线程执行；SQLite 提交成功才 ACK 本地 MQTT。
 * P0 无法持久化（配额耗尽或 DB 异常）时绝不 ACK。
 */
@Service
public class IngressPersistService {

    private static final Logger log = LoggerFactory.getLogger(IngressPersistService.class);

    private final EdgeProperties properties;
    private final EdgeSqliteStore store;
    private final QuotaGuard quotaGuard;
    private final TerminalNotifier notifier;
    private final EdgeMetrics metrics;
    /** Serializes quota admission with the immediately following SQLite commit. */
    private final Object persistenceLock = new Object();

    public IngressPersistService(EdgeProperties properties, EdgeSqliteStore store,
                                 QuotaGuard quotaGuard, TerminalNotifier notifier,
                                 EdgeMetrics metrics) {
        this.properties = properties;
        this.store = store;
        this.quotaGuard = quotaGuard;
        this.notifier = notifier;
        this.metrics = metrics;
    }

    /**
     * 处理一条设备上行（工作线程）。返回 true 表示调用方可以 ACK 本地 MQTT。
     */
    public boolean processDeviceUplink(InboundDelivery delivery, EnvelopeFields envelope) {
        String siteId = properties.getSiteId();
        metrics.received(envelope.messageType(), siteId);
        int priority = isP0(envelope.messageType()) ? 0 : 1;

        String hash = Sha256Util.sha256Hex(delivery.payload());
        IngressRecord record = new IngressRecord();
        record.setTopic(delivery.topic());
        record.setPayload(delivery.payload());
        record.setPayloadSha256(hash);
        record.setDeviceId(envelope.deviceId());
        record.setSourceMessageId(envelope.messageId());
        record.setMessageType(envelope.messageType());
        record.setOccurredAt(envelope.occurredAt());
        record.setPriority(priority);
        record.setReceivedAt(Instant.now().toString());

        List<com.eldercare.edge.model.TerminalNotification> notifications = isP0(envelope.messageType())
                ? notifier.buildPendingNotifications(record)
                : List.of();
        int retainedBytes = delivery.payloadLength() + notifications.stream()
                .mapToInt(notification -> notification.getPayloadJson().getBytes(StandardCharsets.UTF_8).length)
                .sum();

        EdgeSqliteStore.PersistOutcome outcome;
        try {
            synchronized (persistenceLock) {
                quotaGuard.resetUsed(store.usedBytes());
                QuotaGuard.Capacity capacity = quotaGuard.check(priority, retainedBytes);
                if (capacity == QuotaGuard.Capacity.P0_BLOCKED) {
                    metrics.sqliteCapacityRejected("P0_BLOCKED", siteId);
                    metrics.notAcked("p0_quota_exhausted", siteId);
                    return false;
                }
                if (capacity == QuotaGuard.Capacity.NON_P0_DROPPED) {
                    metrics.sqliteCapacityRejected("NON_P0_DROPPED", siteId);
                    return true;
                }
                outcome = store.persistIngressWithNotifications(record, notifications);
                quotaGuard.resetUsed(store.usedBytes());
            }
        } catch (RuntimeException e) {
            // P0 无法持久化时不得 ACK，本地 EMQX 持久会话继续保留该消息
            metrics.notAcked("sqlite_error", siteId);
            log.error("edge_ingress_persist_failed siteId={} deviceId={} sourceMessageId={} "
                            + "messageType={} hashPrefix={} error={}",
                    siteId, envelope.deviceId(), envelope.messageId(), envelope.messageType(),
                    hash.substring(0, Math.min(8, hash.length())), e.getMessage());
            return false;
        }
        switch (outcome) {
            case PERSISTED -> {
                metrics.sqliteCommitted(siteId);
                log.info("edge_ingress_persisted siteId={} deviceId={} sourceMessageId={} "
                                + "messageType={} priority={} hashPrefix={} id={}",
                        siteId, envelope.deviceId(), envelope.messageId(), envelope.messageType(),
                        priority, hash.substring(0, Math.min(8, hash.length())), record.getId());
                if (isP0(envelope.messageType())) {
                    try {
                        notifier.publishPersistedNotifications(notifications);
                    } catch (RuntimeException e) {
                        log.error("edge_notification_publish_deferred siteId={} deviceId={} sourceMessageId={} "
                                        + "messageType={} reason=sqlite_committed_retry_task_will_resume error={}",
                                siteId, envelope.deviceId(), envelope.messageId(), envelope.messageType(),
                                e.getMessage(), e);
                    }
                }
            }
            case DUPLICATE -> log.info("edge_ingress_duplicate siteId={} deviceId={} sourceMessageId={} "
                            + "messageType={} hashPrefix={} reason=qos_redelivery_no_requeue",
                    siteId, envelope.deviceId(), envelope.messageId(), envelope.messageType(),
                    hash.substring(0, Math.min(8, hash.length())));
            case CONFLICT -> {
                metrics.conflict(siteId);
                log.error("edge_ingress_conflict siteId={} deviceId={} sourceMessageId={} "
                                + "messageType={} hashPrefix={} reason=identity_conflict_evidence_kept_no_replay",
                        siteId, envelope.deviceId(), envelope.messageId(), envelope.messageType(),
                        hash.substring(0, Math.min(8, hash.length())));
            }
        }
        return true;
    }

    private boolean isP0(String messageType) {
        return "SOS".equals(messageType) || "FALL".equals(messageType);
    }
}
