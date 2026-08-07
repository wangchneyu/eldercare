package com.eldercare.iot.mapper;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL 集成测试：验证 iot_mq_outbox 的原子插入/去重、条件更新与原子 claim/租约。
 * <p>
 * 依赖本地 PostgreSQL（application-test.yml 配置），未启动数据库时本测试会失败，
 * 需在真实 EMQX/RocketMQ 联调前单独跑通。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
class IotMqOutboxMapperIntegrationTest {

    @Autowired
    private IotMqOutboxMapper outboxMapper;

    @Autowired
    private DataSource dataSource;

    @Test
    void insertOnConflict_duplicateReturnsZeroWithoutException() {
        String deviceId = "DEV-" + UUID.randomUUID();
        String sourceMessageId = "MSG-" + UUID.randomUUID();
        String eventType = "SOS_TRIGGERED";

        IotMqOutbox first = newOutbox(deviceId, sourceMessageId, eventType, "EVT-FIRST");
        int insertedFirst = outboxMapper.insertOnConflict(first);
        assertEquals(1, insertedFirst);

        // 同一 (deviceId, sourceMessage_id, eventType) 再次插入应返回 0，且不抛异常
        IotMqOutbox duplicate = newOutbox(deviceId, sourceMessageId, eventType, "EVT-DUP");
        int insertedDup = outboxMapper.insertOnConflict(duplicate);
        assertEquals(0, insertedDup);

        // 清理
        outboxMapper.deleteById(first.getId());
    }

    @Test
    void insertOnConflict_sameEventIdThrowsBecauseEventIdUnique() {
        String eventId = "EVT-" + UUID.randomUUID();
        String deviceId = "DEV-" + UUID.randomUUID();

        IotMqOutbox first = newOutbox(deviceId, "MSG-A", "SOS_TRIGGERED", eventId);
        assertEquals(1, outboxMapper.insertOnConflict(first));

        // event_id 唯一冲突不得被 ON CONFLICT 吞掉
        IotMqOutbox sameEventId = newOutbox(deviceId + "-OTHER", "MSG-B", "FALL_DETECTED", eventId);
        assertThrows(Exception.class, () -> outboxMapper.insertOnConflict(sameEventId));

        outboxMapper.deleteById(first.getId());
    }

    @Test
    void updateStatusConditionally_onlyUpdatesWhenPending() {
        IotMqOutbox outbox = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID());
        assertEquals(1, outboxMapper.insertOnConflict(outbox));

        // PENDING -> SENT 成功
        int rows = outboxMapper.updateStatusConditionally(
                outbox.getEventId(),
                OutboxStatus.SENT.getCode(),
                OutboxStatus.PENDING.getCode(),
                OffsetDateTime.now(),
                outbox.getClaimedBy()
        );
        assertEquals(1, rows);

        // 再次更新应为 0（已是 SENT）
        int rowsAgain = outboxMapper.updateStatusConditionally(
                outbox.getEventId(),
                OutboxStatus.SENT.getCode(),
                OutboxStatus.PENDING.getCode(),
                OffsetDateTime.now(),
                outbox.getClaimedBy()
        );
        assertEquals(0, rowsAgain);

        outboxMapper.deleteById(outbox.getId());
    }

    @Test
    void updateFailureConditionally_keepsPendingAndIncrementsRetry() {
        IotMqOutbox outbox = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID());
        assertEquals(1, outboxMapper.insertOnConflict(outbox));

        int rows = outboxMapper.updateFailureConditionally(
                outbox.getEventId(),
                OutboxStatus.PENDING.getCode(),
                OutboxStatus.PENDING.getCode(),
                3,
                "mq timeout",
                OffsetDateTime.now().plusSeconds(10),
                outbox.getClaimedBy()
        );
        assertEquals(1, rows);

        IotMqOutbox updated = outboxMapper.selectById(outbox.getId());
        assertEquals(OutboxStatus.PENDING.getCode(), updated.getStatus());
        assertEquals(3, updated.getRetryCount());
        assertEquals("mq timeout", updated.getLastError());

        outboxMapper.deleteById(outbox.getId());
    }

    @Test
    void claimPendingRecords_returnsOnlyUnclaimedOrExpiredRecords() {
        String deviceId = "DEV-" + UUID.randomUUID();
        String sourceMessageId = "MSG-" + UUID.randomUUID();
        IotMqOutbox pending = newOutbox(deviceId, sourceMessageId, "SOS_TRIGGERED", "EVT-" + UUID.randomUUID());
        assertEquals(1, outboxMapper.insertOnConflict(pending));

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseExpireAt = now.plusMinutes(5);
        List<IotMqOutbox> claimed = outboxMapper.claimPendingRecords(10, now, leaseExpireAt, "instance-A");

        assertEquals(1, claimed.size());
        assertEquals(pending.getEventId(), claimed.get(0).getEventId());
        assertEquals("instance-A", claimed.get(0).getClaimedBy());
        assertNotNull(claimed.get(0).getLeaseExpireAt());

        // 同一实例再次 claim（在租约期内）应拿不到
        List<IotMqOutbox> claimedAgain = outboxMapper.claimPendingRecords(10, now, leaseExpireAt, "instance-A");
        assertTrue(claimedAgain.isEmpty());

        outboxMapper.deleteById(pending.getId());
    }

    @Test
    void claimPendingRecords_expiredLeaseIsRecyclable() {
        String deviceId = "DEV-" + UUID.randomUUID();
        String sourceMessageId = "MSG-" + UUID.randomUUID();
        IotMqOutbox pending = newOutbox(deviceId, sourceMessageId, "SOS_TRIGGERED", "EVT-" + UUID.randomUUID());
        assertEquals(1, outboxMapper.insertOnConflict(pending));

        // 先设置一个已过期租约（模拟崩溃未释放）
        outboxMapper.updateLease(pending.getEventId(), OffsetDateTime.now().minusMinutes(1), "crashed-instance");

        OffsetDateTime now = OffsetDateTime.now();
        List<IotMqOutbox> claimed = outboxMapper.claimPendingRecords(10, now,
                now.plusMinutes(5), "recovery-instance");

        assertEquals(1, claimed.size());
        assertEquals("recovery-instance", claimed.get(0).getClaimedBy());

        outboxMapper.deleteById(pending.getId());
    }

    @Test
    void deleteSentOlderThan_deletesOnlySentOlderThanCutoff() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);

        IotMqOutbox oldSent = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusHours(1));
        IotMqOutbox recentSent = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), OffsetDateTime.now().minusDays(1));
        IotMqOutbox oldPending = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.PENDING.getCode(), cutoff.minusHours(1));
        IotMqOutbox oldFailed = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "FALL_DETECTED", "EVT-" + UUID.randomUUID(), OutboxStatus.FAILED.getCode(), cutoff.minusHours(1));
        assertEquals(1, outboxMapper.insertOnConflict(oldSent));
        assertEquals(1, outboxMapper.insertOnConflict(recentSent));
        assertEquals(1, outboxMapper.insertOnConflict(oldPending));
        assertEquals(1, outboxMapper.insertOnConflict(oldFailed));

        assertEquals(1, outboxMapper.deleteSentOlderThan(cutoff, 100));

        // PENDING / FAILED / recent SENT must survive regardless of age.
        assertNotNull(outboxMapper.selectById(recentSent.getId()));
        assertNotNull(outboxMapper.selectById(oldPending.getId()));
        assertNotNull(outboxMapper.selectById(oldFailed.getId()));
        assertNull(outboxMapper.selectById(oldSent.getId()));

        outboxMapper.deleteById(recentSent.getId());
        outboxMapper.deleteById(oldPending.getId());
        outboxMapper.deleteById(oldFailed.getId());
    }

    @Test
    void deleteSentOlderThan_sentAtExactlyAtCutoffIsNotDeleted() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);

        IotMqOutbox atCutoff = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff);
        IotMqOutbox justOlder = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusNanos(1_000_000));
        assertEquals(1, outboxMapper.insertOnConflict(atCutoff));
        assertEquals(1, outboxMapper.insertOnConflict(justOlder));

        // Strict "sent_at < cutoff": the record at exactly the cutoff survives.
        assertEquals(1, outboxMapper.deleteSentOlderThan(cutoff, 100));
        assertNotNull(outboxMapper.selectById(atCutoff.getId()));
        assertNull(outboxMapper.selectById(justOlder.getId()));

        outboxMapper.deleteById(atCutoff.getId());
    }

    @Test
    void deleteSentOlderThan_respectsBatchLimit() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);
        IotMqOutbox first = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusHours(1));
        IotMqOutbox second = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusHours(2));
        IotMqOutbox third = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusHours(3));
        assertEquals(1, outboxMapper.insertOnConflict(first));
        assertEquals(1, outboxMapper.insertOnConflict(second));
        assertEquals(1, outboxMapper.insertOnConflict(third));

        assertEquals(2, outboxMapper.deleteSentOlderThan(cutoff, 2));
        assertEquals(1, outboxMapper.deleteSentOlderThan(cutoff, 2));
        assertEquals(0, outboxMapper.deleteSentOlderThan(cutoff, 2));

        assertNull(outboxMapper.selectById(first.getId()));
        assertNull(outboxMapper.selectById(second.getId()));
        assertNull(outboxMapper.selectById(third.getId()));
    }

    @Test
    void migration_createsPartialSentCleanupIndex() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'iot_mq_outbox'");
             ResultSet rs = ps.executeQuery()) {
            boolean found = false;
            while (rs.next()) {
                String name = rs.getString("indexname");
                String def = rs.getString("indexdef");
                if ("idx_outbox_sent_cleanup".equals(name)
                        && def.contains("(sent_at, id)")
                        && def.contains("'SENT'::text")) {
                    found = true;
                }
            }
            assertTrue(found, "partial index idx_outbox_sent_cleanup (sent_at, id) WHERE status='SENT' missing");
        }
    }

    @Test
    void deleteSentOlderThan_forUpdateSkipLocked_skipsRowsLockedByAnotherTransaction() throws SQLException {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);
        IotMqOutbox oldest = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusHours(3));
        IotMqOutbox second = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusHours(2));
        IotMqOutbox third = newOutbox("DEV-" + UUID.randomUUID(), "MSG-" + UUID.randomUUID(),
                "SOS_TRIGGERED", "EVT-" + UUID.randomUUID(), OutboxStatus.SENT.getCode(), cutoff.minusHours(1));
        assertEquals(1, outboxMapper.insertOnConflict(oldest));
        assertEquals(1, outboxMapper.insertOnConflict(second));
        assertEquals(1, outboxMapper.insertOnConflict(third));

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT id FROM iot_mq_outbox WHERE status = 'SENT' ORDER BY sent_at LIMIT 1 FOR UPDATE")) {
                lock.executeQuery();
            }
            // SKIP LOCKED: the row locked by the other transaction is skipped,
            // the next two are deleted without blocking or failing.
            assertEquals(2, outboxMapper.deleteSentOlderThan(cutoff, 2));
            other.rollback();
        }

        assertNotNull(outboxMapper.selectById(oldest.getId()));
        assertNull(outboxMapper.selectById(second.getId()));
        assertNull(outboxMapper.selectById(third.getId()));

        outboxMapper.deleteById(oldest.getId());
    }

    private IotMqOutbox newOutbox(String deviceId, String sourceMessageId, String eventType, String eventId) {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setId(IdWorker.getId());
        outbox.setEventId(eventId);
        outbox.setDeviceId(deviceId);
        outbox.setSourceMessageId(sourceMessageId);
        outbox.setEventType(eventType);
        outbox.setTopic("elder-sos-event");
        outbox.setTag("SOS_TRIGGERED".equals(eventType) ? "SOS" : "FALL");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", deviceId);
        payload.put("deviceType", "SOS_BUTTON");

        Map<String, Object> rawEnvelope = new LinkedHashMap<>();
        rawEnvelope.put("eventId", eventId);
        rawEnvelope.put("eventType", eventType);
        rawEnvelope.put("schemaVersion", 1);
        rawEnvelope.put("occurredAt", OffsetDateTime.now().toInstant().toString());
        rawEnvelope.put("traceId", UUID.randomUUID().toString());
        rawEnvelope.put("producer", "iot-service");
        rawEnvelope.put("payload", payload);

        outbox.setPayload(payload);
        outbox.setRawEnvelope(rawEnvelope);
        try {
            outbox.setRawEnvelopeJson(new ObjectMapper().writeValueAsString(rawEnvelope));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(0);
        outbox.setCreatedAt(OffsetDateTime.now());
        outbox.setNextRetryAt(OffsetDateTime.now());
        return outbox;
    }

    private IotMqOutbox newOutbox(String deviceId, String sourceMessageId, String eventType,
                                  String eventId, String status, OffsetDateTime sentAt) {
        IotMqOutbox outbox = newOutbox(deviceId, sourceMessageId, eventType, eventId);
        outbox.setStatus(status);
        outbox.setSentAt(sentAt);
        return outbox;
    }
}
