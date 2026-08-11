package com.eldercare.edge.storage;

import com.eldercare.edge.enums.IngressStatus;
import com.eldercare.edge.enums.NotificationStatus;
import com.eldercare.edge.model.IngressRecord;
import com.eldercare.edge.model.TerminalNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * SQLite 存取层（单实例边缘节点唯一可写状态）。
 * <p>
 * 由 Hikari 连接池（maximum-pool-size=1）串行化写入；WAL + synchronous=FULL + 显式事务保证
 * “先落库、提交后才 ACK 本地 MQTT”。identity 唯一键用于识别 QoS 重投与同源不同 hash 冲突。
 */
public class EdgeSqliteStore {

    private static final Logger log = LoggerFactory.getLogger(EdgeSqliteStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public EdgeSqliteStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public void initSchema() {        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS edge_ingress_message (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    topic TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    source_message_id TEXT NOT NULL,
                    message_type TEXT NOT NULL,
                    occurred_at TEXT NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 1,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    lease_until INTEGER,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    received_at TEXT NOT NULL,
                    forwarded_at TEXT
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_ingress_replayable"
                + " ON edge_ingress_message(priority, id) WHERE status IN ('PENDING','REPLAYING')");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_ingress_status ON edge_ingress_message(status)");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS edge_ingress_identity (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id TEXT NOT NULL,
                    source_message_id TEXT NOT NULL,
                    message_type TEXT NOT NULL,
                    canonical_message_id INTEGER NOT NULL,
                    canonical_sha256 TEXT NOT NULL,
                    UNIQUE(device_id, source_message_id, message_type)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS edge_ingress_conflict (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    identity_id INTEGER NOT NULL,
                    first_message_id INTEGER NOT NULL,
                    second_payload BLOB NOT NULL,
                    first_sha256 TEXT NOT NULL,
                    second_sha256 TEXT NOT NULL,
                    detected_at TEXT NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS edge_terminal_notification (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    notification_id TEXT NOT NULL,
                    message_id INTEGER NOT NULL,
                    terminal_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    next_attempt_at INTEGER NOT NULL,
                    broker_accepted_at TEXT,
                    receipted_at TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(notification_id, terminal_id)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_due"
                + " ON edge_terminal_notification(status, next_attempt_at)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_message"
                + " ON edge_terminal_notification(message_id)");
    }

    // ───────────────────────── 入站持久化 ─────────────────────────

    public enum PersistOutcome {
        /** 新记录落库。 */
        PERSISTED,
        /** 完全相同的 (deviceId, messageId, messageType, hash)：QoS 重投，不重复入队。 */
        DUPLICATE,
        /** 同一身份不同 hash：冲突证据留证，不覆盖、不自动重放。 */
        CONFLICT
    }

    public record IdentityRow(long id, long canonicalMessageId, String canonicalSha256) {
    }

    /**
     * 写原始消息行 + 唯一身份。同一 (deviceId, sourceMessageId, messageType) 下
     * 相同 hash 判定为 QoS 重投（返回 {@link PersistOutcome#DUPLICATE} 并移除刚插入的行）；
     * 不同 hash 保留冲突证据（返回 {@link PersistOutcome#CONFLICT}）。
     * 调用方在 SQLite 事务提交后才 ACK 本地 MQTT。
     */
    public PersistOutcome persistIngress(IngressRecord record) {
        return persistIngressWithNotifications(record, List.of());
    }

    /**
     * 在同一 SQLite 事务中写入原始消息、去重身份和 P0 的逐终端待通知记录。
     * <p>
     * 这是本地 ACK 的持久化边界：事务失败时三类数据都不应留下，调用方不能 ACK MQTT。
     */
    public PersistOutcome persistIngressWithNotifications(IngressRecord record,
                                                           List<TerminalNotification> notifications) {
        PersistOutcome outcome = transactionTemplate.execute(status -> persistIngressInTransaction(record, notifications));
        if (outcome == null) {
            throw new IllegalStateException("SQLite 入站事务未返回处理结果");
        }
        return outcome;
    }

    private PersistOutcome persistIngressInTransaction(IngressRecord record,
                                                        List<TerminalNotification> notifications) {
        Long newId = jdbcTemplate.queryForObject("""
                INSERT INTO edge_ingress_message
                    (topic, payload, payload_sha256, device_id, source_message_id, message_type,
                     occurred_at, priority, status, attempts, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
                RETURNING id
                """, Long.class,
                record.getTopic(), record.getPayload(), record.getPayloadSha256(),
                record.getDeviceId(), record.getSourceMessageId(), record.getMessageType(),
                record.getOccurredAt(), record.getPriority(), record.getReceivedAt());
        record.setId(newId);

        IdentityRow identity = findIdentity(record.getDeviceId(),
                record.getSourceMessageId(), record.getMessageType());
        if (identity == null) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO edge_ingress_identity
                            (device_id, source_message_id, message_type, canonical_message_id, canonical_sha256)
                        VALUES (?, ?, ?, ?, ?)
                        """, record.getDeviceId(), record.getSourceMessageId(), record.getMessageType(),
                        newId, record.getPayloadSha256());
            } catch (DataIntegrityViolationException e) {
                // 并发兜底：单写者连接池下几乎不可能，重新读取 identity 走冲突分支
                identity = findIdentity(record.getDeviceId(),
                        record.getSourceMessageId(), record.getMessageType());
                if (identity == null) {
                    log.error("edge_sqlite_integrity deviceId={} sourceMessageId={} messageType={} reason={}",
                            record.getDeviceId(), record.getSourceMessageId(), record.getMessageType(),
                            "identity_missing_after_conflict");
                    return PersistOutcome.CONFLICT;
                }
            }
            if (identity == null) {
                for (TerminalNotification notification : notifications) {
                    notification.setMessageId(newId);
                    long notificationId = insertNotification(notification);
                    notification.setId(notificationId);
                }
                return PersistOutcome.PERSISTED;
            }
        }
        boolean sameHash = identity.canonicalSha256().equals(record.getPayloadSha256());
        if (sameHash) {
            deleteMessageById(newId);
            return PersistOutcome.DUPLICATE;
        }
        jdbcTemplate.update("""
                INSERT INTO edge_ingress_conflict
                    (identity_id, first_message_id, second_payload, first_sha256, second_sha256, detected_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, identity.id(), identity.canonicalMessageId(), record.getPayload(),
                identity.canonicalSha256(), record.getPayloadSha256(), record.getReceivedAt());
        deleteMessageById(newId);
        return PersistOutcome.CONFLICT;
    }

    public IdentityRow findIdentity(String deviceId, String sourceMessageId, String messageType) {
        List<IdentityRow> rows = jdbcTemplate.query("""
                SELECT id, canonical_message_id, canonical_sha256
                FROM edge_ingress_identity
                WHERE device_id = ? AND source_message_id = ? AND message_type = ?
                """, (rs, rowNum) -> new IdentityRow(rs.getLong("id"),
                        rs.getLong("canonical_message_id"), rs.getString("canonical_sha256")),
                deviceId, sourceMessageId, messageType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteMessageById(long id) {
        jdbcTemplate.update("DELETE FROM edge_ingress_message WHERE id = ?", id);
    }

    // ───────────────────────── 通知 ─────────────────────────

    public long insertNotification(TerminalNotification notification) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO edge_terminal_notification
                    (notification_id, message_id, terminal_id, payload_json, status, next_attempt_at, attempts)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                RETURNING id
                """, Long.class, notification.getNotificationId(), notification.getMessageId(),
                notification.getTerminalId(), notification.getPayloadJson(),
                notification.getStatus().name(), notification.getNextAttemptAt());
    }

    public TerminalNotification findNotification(String notificationId, String terminalId) {
        List<TerminalNotification> rows = jdbcTemplate.query("""
                SELECT id, notification_id, message_id, terminal_id, payload_json, status,
                       next_attempt_at, broker_accepted_at, receipted_at, attempts
                FROM edge_terminal_notification
                WHERE notification_id = ? AND terminal_id = ?
                """, NOTIFICATION_MAPPER, notificationId, terminalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 标记 QoS 1 PUBACK 已收到（仅表示 Broker 接受，不代表终端展示）；同时排定下次未回执重投时间。 */
    public boolean markBrokerAccepted(long notificationId, String acceptedAt, long nextAttemptAt) {
        return jdbcTemplate.update("""
                UPDATE edge_terminal_notification
                SET status = 'BROKER_ACCEPTED', broker_accepted_at = ?, next_attempt_at = ?,
                    attempts = attempts + 1
                WHERE id = ?
                """, acceptedAt, nextAttemptAt, notificationId) == 1;
    }

    /** 匹配终端、匹配 ID 的 DISPLAYED 回执才允许进入 RECEIPTED；重复回执幂等。 */
    public boolean markReceipted(String notificationId, String terminalId, String receiptedAt) {
        return jdbcTemplate.update("""
                UPDATE edge_terminal_notification
                SET status = 'RECEIPTED', receipted_at = ?
                WHERE notification_id = ? AND terminal_id = ? AND status != 'RECEIPTED'
                """, receiptedAt, notificationId, terminalId) == 1;
    }

    /** 未回执通知按固定间隔重投同一份 payload（同一 notificationId）。 */
    public List<TerminalNotification> listNotificationsDue(long now, int batchSize) {
        return jdbcTemplate.query("""
                SELECT id, notification_id, message_id, terminal_id, payload_json, status,
                       next_attempt_at, broker_accepted_at, receipted_at, attempts
                FROM edge_terminal_notification
                WHERE status != 'RECEIPTED' AND next_attempt_at <= ?
                ORDER BY next_attempt_at ASC, id ASC
                LIMIT ?
                """, NOTIFICATION_MAPPER, now, batchSize);
    }

    public boolean markNotificationAttempt(long notificationId, long nextAttemptAt) {
        return jdbcTemplate.update("""
                UPDATE edge_terminal_notification
                SET attempts = attempts + 1, next_attempt_at = ?
                WHERE id = ?
                """, nextAttemptAt, notificationId) == 1;
    }

    public long countUnreceiptedP0Notifications() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM edge_terminal_notification n
                JOIN edge_ingress_message m ON m.id = n.message_id
                WHERE n.status != 'RECEIPTED' AND m.priority = 0
                """, Long.class);
        return count == null ? 0 : count;
    }

    // ───────────────────────── 补传 ─────────────────────────

    /** 领取可重放批次：P0 优先、同优先级按入队 ID FIFO；同时续租标记 REPLAYING。 */
    public List<IngressRecord> claimReplayable(int batchSize, long leaseUntilMillis, long nowMillis) {
        List<IngressRecord> candidates = jdbcTemplate.query("""
                SELECT id, topic, payload, payload_sha256, device_id, source_message_id,
                       message_type, occurred_at, priority, status, lease_until, attempts,
                       last_error, received_at, forwarded_at
                FROM edge_ingress_message
                WHERE status = 'PENDING'
                   OR (status = 'REPLAYING' AND (lease_until IS NULL OR lease_until < ?))
                ORDER BY priority ASC, id ASC
                LIMIT ?
                """, INGRESS_MAPPER, nowMillis, batchSize);
        return candidates.stream()
                .filter(candidate -> claim(candidate.getId(), leaseUntilMillis, nowMillis))
                .toList();
    }

    private boolean claim(long id, long leaseUntilMillis, long nowMillis) {
        int updated = jdbcTemplate.update("""
                UPDATE edge_ingress_message
                SET status = 'REPLAYING', lease_until = ?, attempts = attempts + 1
                WHERE id = ?
                  AND (status = 'PENDING' OR (status = 'REPLAYING' AND (lease_until IS NULL OR lease_until < ?)))
                """, leaseUntilMillis, id, nowMillis);
        return updated == 1;
    }

    public boolean markForwarded(long id, String forwardedAt) {
        return jdbcTemplate.update("""
                UPDATE edge_ingress_message
                SET status = 'FORWARDED', forwarded_at = ?, lease_until = NULL
                WHERE id = ? AND status = 'REPLAYING'
                """, forwardedAt, id) == 1;
    }

    /** 发布失败/结果未知：回到可重放状态，记录错误；承认 at-least-once。 */
    public void markReplayFailure(long id, String lastError) {
        jdbcTemplate.update("""
                UPDATE edge_ingress_message
                SET status = 'PENDING', lease_until = NULL, last_error = ?
                WHERE id = ? AND status = 'REPLAYING'
                """, lastError, id);
    }

    /** SHA-256 与落库值不匹配：仅保留证据、绝不重放。 */
    public boolean markCorrupted(long id, String reason) {
        return jdbcTemplate.update("""
                UPDATE edge_ingress_message
                SET status = 'CORRUPTED', lease_until = NULL, last_error = ?
                WHERE id = ? AND status = 'REPLAYING'
                """, reason, id) == 1;
    }

    public IngressRecord findIngressById(long id) {
        List<IngressRecord> rows = jdbcTemplate.query("""
                SELECT id, topic, payload, payload_sha256, device_id, source_message_id,
                       message_type, occurred_at, priority, status, lease_until, attempts,
                       last_error, received_at, forwarded_at
                FROM edge_ingress_message WHERE id = ?
                """, INGRESS_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 仅测试用：模拟磁盘/软件故障导致的 payload 篡改，验证 checksum 留证逻辑。 */
    public void updateRawPayloadForCorruptionTest(long id, byte[] tamperedPayload) {
        jdbcTemplate.update("UPDATE edge_ingress_message SET payload = ? WHERE id = ?",
                tamperedPayload, id);
    }

    // ───────────────────────── 配额与指标 ─────────────────────────

    /** 已用字节 ≈ 原始消息 payload 总长 + 冲突证据 payload 总长。 */
    public long usedBytes() {
        Long messages = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM edge_ingress_message", Long.class);
        Long conflicts = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(LENGTH(second_payload)), 0) FROM edge_ingress_conflict", Long.class);
        Long notifications = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(LENGTH(payload_json)), 0) FROM edge_terminal_notification", Long.class);
        return (messages == null ? 0 : messages)
                + (conflicts == null ? 0 : conflicts)
                + (notifications == null ? 0 : notifications);
    }

    public long oldestIngressAgeMillis(long nowMillis) {
        List<String> oldest = jdbcTemplate.queryForList(
                "SELECT received_at FROM edge_ingress_message WHERE status != 'FORWARDED' "
                        + "AND status != 'CORRUPTED' ORDER BY received_at ASC LIMIT 1", String.class);
        if (oldest.isEmpty()) {
            return 0;
        }
        try {
            return nowMillis - java.time.Instant.parse(oldest.get(0)).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    public long p0PendingDepth() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM edge_ingress_message
                WHERE priority = 0 AND status IN ('PENDING', 'REPLAYING')
                """, Long.class);
        return count == null ? 0 : count;
    }

    public long countByStatus(IngressStatus status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM edge_ingress_message WHERE status = ?", Long.class, status.name());
        return count == null ? 0 : count;
    }

    // ───────────────────────── 终态清理 ─────────────────────────

    /** 只删除 FORWARDED 原始记录（按 forwarded_at 短审计保留期）。 */
    public List<Long> listForwardedOlderThan(String cutoffIso, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT message.id FROM edge_ingress_message message
                WHERE message.status = 'FORWARDED' AND message.forwarded_at IS NOT NULL
                  AND message.forwarded_at < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM edge_terminal_notification notification
                      WHERE notification.message_id = message.id AND notification.status != 'RECEIPTED'
                  )
                ORDER BY message.forwarded_at ASC LIMIT ?
                """, Long.class, cutoffIso, limit);
    }

    /** 只删除已回执通知（按 receipted_at 短审计保留期）。 */
    public List<Long> listReceiptedOlderThan(String cutoffIso, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id FROM edge_terminal_notification
                WHERE status = 'RECEIPTED' AND receipted_at IS NOT NULL AND receipted_at < ?
                ORDER BY receipted_at ASC LIMIT ?
                """, Long.class, cutoffIso, limit);
    }

    /** Conflict evidence follows the same bounded local audit retention as terminal state. */
    public List<Long> listConflictsOlderThan(String cutoffIso, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id FROM edge_ingress_conflict
                WHERE detected_at < ?
                ORDER BY detected_at ASC, id ASC LIMIT ?
                """, Long.class, cutoffIso, limit);
    }

    /**
     * Identity rows may leave only after their canonical message and every conflict evidence row are gone.
     * This keeps the bounded local QoS dedupe window without allowing metadata to grow forever.
     */
    public List<Long> listOrphanIdentities(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT identity.id FROM edge_ingress_identity identity
                WHERE NOT EXISTS (
                    SELECT 1 FROM edge_ingress_message message
                    WHERE message.id = identity.canonical_message_id
                )
                  AND NOT EXISTS (
                    SELECT 1 FROM edge_ingress_conflict conflict
                    WHERE conflict.identity_id = identity.id
                )
                ORDER BY identity.id ASC LIMIT ?
                """, Long.class, limit);
    }

    public void deleteForwardedByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM edge_ingress_message WHERE id IN (%s)"
                .formatted(ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0")));
    }

    public void deleteNotificationsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM edge_terminal_notification WHERE id IN (%s)"
                .formatted(ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0")));
    }

    public void deleteConflictsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM edge_ingress_conflict WHERE id IN (%s)"
                .formatted(ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0")));
    }

    public void deleteIdentitiesByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM edge_ingress_identity WHERE id IN (%s)"
                .formatted(ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0")));
    }

    // ───────────────────────── RowMapper ─────────────────────────

    /** 关闭底层连接池（测试与停机时调用）。 */
    public void close() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("edge_sqlite_close_failed error={}", e.getMessage());
            }
        }
    }

    private static final RowMapper<IngressRecord> INGRESS_MAPPER = (rs, rowNum) -> mapIngress(rs);

    private static IngressRecord mapIngress(ResultSet rs) throws SQLException {
        IngressRecord record = new IngressRecord();
        record.setId(rs.getLong("id"));
        record.setTopic(rs.getString("topic"));
        record.setPayload(rs.getBytes("payload"));
        record.setPayloadSha256(rs.getString("payload_sha256"));
        record.setDeviceId(rs.getString("device_id"));
        record.setSourceMessageId(rs.getString("source_message_id"));
        record.setMessageType(rs.getString("message_type"));
        record.setOccurredAt(rs.getString("occurred_at"));
        record.setPriority(rs.getInt("priority"));
        record.setStatus(IngressStatus.valueOf(rs.getString("status")));
        long lease = rs.getLong("lease_until");
        record.setLeaseUntil(rs.wasNull() ? null : lease);
        record.setAttempts(rs.getInt("attempts"));
        record.setLastError(rs.getString("last_error"));
        record.setReceivedAt(rs.getString("received_at"));
        record.setForwardedAt(rs.getString("forwarded_at"));
        return record;
    }

    private static final RowMapper<TerminalNotification> NOTIFICATION_MAPPER = (rs, rowNum) -> {
        TerminalNotification n = new TerminalNotification();
        n.setId(rs.getLong("id"));
        n.setNotificationId(rs.getString("notification_id"));
        n.setMessageId(rs.getLong("message_id"));
        n.setTerminalId(rs.getString("terminal_id"));
        n.setPayloadJson(rs.getString("payload_json"));
        n.setStatus(NotificationStatus.valueOf(rs.getString("status")));
        n.setNextAttemptAt(rs.getLong("next_attempt_at"));
        n.setBrokerAcceptedAt(rs.getString("broker_accepted_at"));
        n.setReceiptedAt(rs.getString("receipted_at"));
        n.setAttempts(rs.getInt("attempts"));
        return n;
    };
}
