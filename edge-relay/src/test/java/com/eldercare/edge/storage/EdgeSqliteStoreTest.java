package com.eldercare.edge.storage;

import com.eldercare.edge.enums.IngressStatus;
import com.eldercare.edge.enums.NotificationStatus;
import com.eldercare.edge.model.IngressRecord;
import com.eldercare.edge.model.TerminalNotification;
import com.eldercare.edge.util.Sha256Util;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 存储层集成测试（临时文件库，无需 Docker）：
 * 去重、冲突留证、补传领取/续租/FIFO/P0 优先、重启恢复、checksum 异常留证、终态清理。
 */
class EdgeSqliteStoreTest {

    @TempDir
    Path tempDir;

    private EdgeSqliteStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = newStore(tempDir.resolve("test-" + System.nanoTime() + ".db"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    static EdgeSqliteStore newStore(Path db) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + db);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        HikariDataSource dataSource = new HikariDataSource(config);
        EdgeSqliteStore store = new EdgeSqliteStore(dataSource);
        store.initSchema();
        return store;
    }

    private IngressRecord newRecord(String deviceId, String messageId, String type,
                                    byte[] payload, int priority) {
        IngressRecord record = new IngressRecord();
        record.setTopic("elder/P001/SOS_BUTTON/" + deviceId + "/up/event");
        record.setPayload(payload);
        record.setPayloadSha256(Sha256Util.sha256Hex(payload));
        record.setDeviceId(deviceId);
        record.setSourceMessageId(messageId);
        record.setMessageType(type);
        record.setOccurredAt(Instant.now().toString());
        record.setPriority(priority);
        record.setReceivedAt(Instant.now().toString());
        return record;
    }

    @Test
    void persist_insertsRowAndIdentity() {
        byte[] payload = "{\"messageId\":\"m1\"}".getBytes();
        EdgeSqliteStore.PersistOutcome outcome = store.persistIngress(
                newRecord("DEV-1", "m1", "SOS", payload, 0));
        assertEquals(EdgeSqliteStore.PersistOutcome.PERSISTED, outcome);
        assertNotNull(store.findIngressById(1L));
        assertEquals(IngressStatus.PENDING, store.findIngressById(1L).getStatus());
        assertEquals(1, store.countByStatus(IngressStatus.PENDING));
    }

    @Test
    void duplicate_sameIdentitySameHash_removesRowAndReturnsDuplicate() {
        byte[] payload = "{\"messageId\":\"m1\"}".getBytes();
        assertEquals(EdgeSqliteStore.PersistOutcome.PERSISTED,
                store.persistIngress(newRecord("DEV-1", "m1", "SOS", payload, 0)));
        assertEquals(EdgeSqliteStore.PersistOutcome.DUPLICATE,
                store.persistIngress(newRecord("DEV-1", "m1", "SOS", payload, 0)));
        assertEquals(1, store.countByStatus(IngressStatus.PENDING));
        assertEquals(0, store.countByStatus(IngressStatus.FORWARDED));
        assertNull(store.findIngressById(2L), "重复投递不能新增记录");
    }

    @Test
    void persistWithNotifications_rollsBackRawIdentityAndNotificationsAsOneTransaction() {
        byte[] payload = "{\"messageId\":\"m1\"}".getBytes();
        TerminalNotification first = newNotification("n1", "caregiver-01", "{\"n\":1}");
        TerminalNotification duplicate = newNotification("n1", "caregiver-01", "{\"n\":2}");

        assertThrows(RuntimeException.class, () -> store.persistIngressWithNotifications(
                newRecord("DEV-1", "m1", "SOS", payload, 0), List.of(first, duplicate)));

        assertEquals(0, store.countByStatus(IngressStatus.PENDING));
        assertNull(store.findIdentity("DEV-1", "m1", "SOS"));
        assertNull(store.findNotification("n1", "caregiver-01"));
    }

    @Test
    void conflict_sameIdentityDifferentHash_keepsEvidenceAndDeletesSecondRow() {
        byte[] first = "{\"messageId\":\"m1\",\"v\":1}".getBytes();
        byte[] second = "{\"messageId\":\"m1\",\"v\":2}".getBytes();
        assertEquals(EdgeSqliteStore.PersistOutcome.PERSISTED,
                store.persistIngress(newRecord("DEV-1", "m1", "SOS", first, 0)));
        assertEquals(EdgeSqliteStore.PersistOutcome.CONFLICT,
                store.persistIngress(newRecord("DEV-1", "m1", "SOS", second, 0)));
        assertNull(store.findIngressById(2L), "冲突副本不能自动入队");
        EdgeSqliteStore.IdentityRow identity =
                store.findIdentity("DEV-1", "m1", "SOS");
        assertNotNull(identity);
        assertEquals(Sha256Util.sha256Hex(first), identity.canonicalSha256());
        long used = store.usedBytes();
        assertTrue(used >= first.length, "usedBytes 应计入冲突证据 payload");
    }

    @Test
    void claimReplayable_p0FirstThenFifoByInsertionOrder() {
        byte[] p = "{\"v\":1}".getBytes();
        store.persistIngress(newRecord("D1", "ms1", "HEARTBEAT", p, 1));
        store.persistIngress(newRecord("D2", "ms2", "SOS", p, 0));
        store.persistIngress(newRecord("D3", "ms3", "VITAL_SIGN", p, 1));
        List<IngressRecord> claimed = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis());
        assertEquals(3, claimed.size());
        assertEquals("SOS", claimed.get(0).getMessageType());
        assertEquals("HEARTBEAT", claimed.get(1).getMessageType());
        assertEquals("VITAL_SIGN", claimed.get(2).getMessageType());
        assertEquals(IngressStatus.REPLAYING, store.findIngressById(claimed.get(0).getId()).getStatus());
    }

    @Test
    void claimRespectsLease_expiredReplayingReturnedToClaimable() {
        byte[] p = "{\"v\":1}".getBytes();
        store.persistIngress(newRecord("D1", "ms1", "SOS", p, 0));
        store.claimReplayable(10, System.currentTimeMillis() + 5000L, System.currentTimeMillis());
        // 租约未过期 → 不可领取
        assertEquals(0, store.claimReplayable(10, Long.MAX_VALUE,
                System.currentTimeMillis() + 1000L).size());
        // 租约过期 → 重新领取
        assertEquals(1, store.claimReplayable(10, Long.MAX_VALUE,
                System.currentTimeMillis() + 6000L).size());
    }

    @Test
    void markForwarded_onlyFromReplaying_andCorruptNeverReplayed() {
        byte[] p = "{\"v\":1}".getBytes();
        store.persistIngress(newRecord("D1", "ms1", "SOS", p, 0));
        List<IngressRecord> claimed = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis());
        long id = claimed.get(0).getId();
        assertFalse(store.markForwarded(999L, Instant.now().toString()));
        assertTrue(store.markForwarded(id, Instant.now().toString()));
        assertEquals(IngressStatus.FORWARDED, store.findIngressById(id).getStatus());
        assertFalse(store.markCorrupted(id, "already_forwarded_ignored"));
    }

    @Test
    void markReplayFailure_restoresToPendingForAtLeastOnce() {
        byte[] p = "{\"v\":1}".getBytes();
        store.persistIngress(newRecord("D1", "ms1", "SOS", p, 0));
        long id = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis()).get(0).getId();
        store.markReplayFailure(id, "connected_lost");
        IngressRecord record = store.findIngressById(id);
        assertEquals(IngressStatus.PENDING, record.getStatus());
        assertNull(record.getLeaseUntil());
        assertTrue(record.getAttempts() >= 1);
    }

    @Test
    void reopenRecoversPendingAndExpiredLease_expiredReplayingBecomesClaimable() throws Exception {
        Path dbPath = tempDir.resolve("reopen-" + System.nanoTime() + ".db");
        byte[] p = "{\"v\":1}".getBytes();
        long id;
        try (HikariDataSource ds = openDataSource(dbPath)) {
            EdgeSqliteStore store1 = new EdgeSqliteStore(ds);
            store1.initSchema();
            store1.persistIngress(newRecord("D1", "ms1", "SOS", p, 0));
            store1.claimReplayable(10, System.currentTimeMillis() + 1000L, System.currentTimeMillis());
            id = store1.findIngressById(1L).getId();
        }
        try (HikariDataSource ds = openDataSource(dbPath)) {
            EdgeSqliteStore store2 = new EdgeSqliteStore(ds);
            store2.initSchema();
            IngressRecord record = store2.findIngressById(id);
            assertNotNull(record, "重启后记录必须仍在");
            assertEquals(IngressStatus.REPLAYING, record.getStatus());
            // 租约已过期 → 可重新领取并补传
            List<IngressRecord> claimed = store2.claimReplayable(10, Long.MAX_VALUE,
                    System.currentTimeMillis() + 2000L);
            assertEquals(1, claimed.size());
            assertArrayEquals(p, claimed.get(0).getPayload(), "原始字节必须保持不变");
        }
    }

    @Test
    void corruptedDetection_requiresShaMatchAtReplay() {
        byte[] p = "{\"v\":1}".getBytes();
        store.persistIngress(newRecord("D1", "ms1", "SOS", p, 0));
        long id = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis()).get(0).getId();
        assertTrue(store.markCorrupted(id, "sha256_mismatch_detect_test"));
        assertEquals(IngressStatus.CORRUPTED, store.findIngressById(id).getStatus());
    }

    @Test
    void cleanup_deletesOnlyForwardedMessagesAndReceiptedNotifications() {
        byte[] p = "{\"v\":1}".getBytes();
        String cutoff = Instant.now().minusSeconds(3600).toString();
        String olderThanCutoff = Instant.now().minusSeconds(7200).toString();
        store.persistIngress(newRecord("D1", "ms1", "SOS", p, 0));
        long forwardedId = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis()).get(0).getId();
        store.markForwarded(forwardedId, olderThanCutoff);

        store.persistIngress(newRecord("D2", "ms2", "VITAL_SIGN", p, 1));
        long pendingId = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis()).get(0).getId();
        store.markReplayFailure(pendingId, "keep_pending");

        TerminalNotification notification = new TerminalNotification();
        notification.setNotificationId("n1");
        notification.setMessageId(forwardedId);
        notification.setTerminalId("caregiver-01");
        notification.setPayloadJson("{}");
        notification.setStatus(NotificationStatus.PENDING);
        notification.setNextAttemptAt(0);
        long notificationId = store.insertNotification(notification);
        assertTrue(store.markReceipted("n1", "caregiver-01", olderThanCutoff));

        store.deleteForwardedByIds(store.listForwardedOlderThan(cutoff, 100));
        store.deleteNotificationsByIds(store.listReceiptedOlderThan(cutoff, 100));

        assertNull(store.findIngressById(forwardedId), "FORWARDED 终态可清理");
        assertNotNull(store.findIngressById(pendingId), "PENDING 永不清理");
        assertNull(store.findNotification("n1", "caregiver-01"), "已回执通知可清理");
    }

    @Test
    void cleanup_keepsForwardedIngressWhileAnyLinkedNotificationIsUnreceipted() {
        byte[] payload = "{\"v\":1}".getBytes();
        String cutoff = Instant.now().minusSeconds(3600).toString();
        String olderThanCutoff = Instant.now().minusSeconds(7200).toString();
        store.persistIngress(newRecord("D1", "ms1", "SOS", payload, 0));
        long messageId = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis()).get(0).getId();
        store.markForwarded(messageId, olderThanCutoff);
        TerminalNotification notification = newNotification("n1", "caregiver-01", "{}");
        notification.setMessageId(messageId);
        store.insertNotification(notification);

        assertTrue(store.listForwardedOlderThan(cutoff, 100).isEmpty());
        assertTrue(store.markReceipted("n1", "caregiver-01", olderThanCutoff));
        assertEquals(List.of(messageId), store.listForwardedOlderThan(cutoff, 100));
    }

    @Test
    void cleanup_retainsIdentityUntilItsMessageAndConflictEvidenceAreBothGone() {
        byte[] first = "{\"messageId\":\"m1\",\"v\":1}".getBytes();
        byte[] conflicting = "{\"messageId\":\"m1\",\"v\":2}".getBytes();
        String cutoff = Instant.now().plusSeconds(60).toString();
        store.persistIngress(newRecord("D1", "m1", "SOS", first, 0));
        store.persistIngress(newRecord("D1", "m1", "SOS", conflicting, 0));
        long messageId = store.claimReplayable(10, Long.MAX_VALUE, System.currentTimeMillis()).get(0).getId();
        store.markForwarded(messageId, Instant.now().minusSeconds(3600).toString());
        EdgeSqliteStore.IdentityRow identity = store.findIdentity("D1", "m1", "SOS");
        assertNotNull(identity);

        store.deleteForwardedByIds(store.listForwardedOlderThan(cutoff, 100));
        assertNotNull(store.findIdentity("D1", "m1", "SOS"),
                "conflict evidence must preserve the dedupe identity");
        store.deleteConflictsByIds(store.listConflictsOlderThan(cutoff, 100));
        assertEquals(List.of(identity.id()), store.listOrphanIdentities(100));
        store.deleteIdentitiesByIds(store.listOrphanIdentities(100));
        assertNull(store.findIdentity("D1", "m1", "SOS"));
    }

    @Test
    void p0QueuesAndCounts_reportDepthAndOldestAge() {
        byte[] p = "{\"v\":1}".getBytes();
        store.persistIngress(newRecord("D1", "ms1", "SOS", p, 0));
        store.persistIngress(newRecord("D2", "ms2", "FALL", p, 0));
        store.persistIngress(newRecord("D3", "ms3", "HEARTBEAT", p, 1));
        assertEquals(2, store.p0PendingDepth());
        assertTrue(store.oldestIngressAgeMillis(System.currentTimeMillis() + 100_000L) > 0);
        assertEquals(0, store.countUnreceiptedP0Notifications());
    }

    @Test
    void unreceiptedP0NotificationCount_joinsMessagePriority() {
        store.persistIngress(newRecord("D1", "ms1", "SOS", "{}".getBytes(), 0));
        long messageId = 1L;
        TerminalNotification n = new TerminalNotification();
        n.setNotificationId("n1");
        n.setMessageId(messageId);
        n.setTerminalId("caregiver-01");
        n.setPayloadJson("{}");
        n.setStatus(NotificationStatus.PENDING);
        n.setNextAttemptAt(0);
        store.insertNotification(n);
        assertEquals(1, store.countUnreceiptedP0Notifications());
    }

    private TerminalNotification newNotification(String notificationId, String terminalId, String payloadJson) {
        TerminalNotification notification = new TerminalNotification();
        notification.setNotificationId(notificationId);
        notification.setTerminalId(terminalId);
        notification.setPayloadJson(payloadJson);
        notification.setStatus(NotificationStatus.PENDING);
        notification.setNextAttemptAt(0);
        return notification;
    }

    private HikariDataSource openDataSource(Path db) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + db);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }
}
