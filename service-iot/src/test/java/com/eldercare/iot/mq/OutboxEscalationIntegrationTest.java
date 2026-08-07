package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.entity.IotMqOutboxEscalation;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C16-3 PostgreSQL integration tests: one-shot window escalation semantics.
 * <p>
 * Proves the strict cutoff boundary, one-time escalation (including concurrent
 * instances), status/lease/frozen-envelope immutability, still claimable by the
 * retry task, recovery to SENT with the original envelope while the audit fields
 * survive, C16-2 non-deletion of escalated PENDING records, and the V008 index.
 * RocketMQTemplate is mocked; no shared Broker is involved.
 */
@SpringBootTest(properties = {
        "iot.outbox.retry.fixed-delay-ms=86400000",
        "iot.outbox.escalation.fixed-delay-ms=86400000"
})
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
class OutboxEscalationIntegrationTest {

    private static final String REASON = OutboxEscalationTask.ESCALATION_REASON;

    @Autowired
    private IotMqOutboxMapper outboxMapper;
    @Autowired
    private SosEventProducer sosEventProducer;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private DataSource dataSource;

    private final List<Long> insertedIds = new ArrayList<>();

    @BeforeEach
    void resetMqMock() {
        reset(rocketMQTemplate);
        SendResult ok = new SendResult();
        ok.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(ok);
    }

    @AfterEach
    void cleanup() {
        insertedIds.forEach(id -> outboxMapper.deleteById(id));
        insertedIds.clear();
    }

    @Test
    void escalate_withinWindow_doesNotEscalate() {
        OffsetDateTime now = OffsetDateTime.now();
        IotMqOutbox recent = insertPending(now.minusMinutes(10));

        List<IotMqOutboxEscalation> escalated =
                outboxMapper.escalatePendingOlderThan(now.minusMinutes(30), 100, now, REASON);

        assertTrue(escalated.isEmpty());
        assertNull(reload(recent).getEscalatedAt());
    }

    @Test
    void escalate_strictBoundary_createdAtEqualToCutoffSurvives() {
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime cutoff = base.minusMinutes(30);
        IotMqOutbox atBoundary = insertPending(cutoff);
        IotMqOutbox justOver = insertPending(cutoff.minusSeconds(1));

        List<IotMqOutboxEscalation> escalated = outboxMapper.escalatePendingOlderThan(cutoff, 100, base, REASON);

        assertEquals(1, escalated.size());
        assertEquals(justOver.getEventId(), escalated.get(0).getEventId());
        assertNull(reload(atBoundary).getEscalatedAt());
        assertNotNull(reload(justOver).getEscalatedAt());
    }

    @Test
    void escalate_eachRecordOnlyOnce() {
        OffsetDateTime now = OffsetDateTime.now();
        IotMqOutbox overdue = insertPending(now.minusHours(1));

        List<IotMqOutboxEscalation> first = outboxMapper.escalatePendingOlderThan(now.minusMinutes(30), 100, now, REASON);
        List<IotMqOutboxEscalation> second = outboxMapper.escalatePendingOlderThan(now.minusMinutes(30), 100, now, REASON);

        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
        assertEquals(REASON, reload(overdue).getEscalationReason());
    }

    @Test
    void escalate_concurrentInstances_escalateEachRecordAtMostOnce() throws InterruptedException {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime cutoff = now.minusMinutes(30);
        int total = 30;
        for (int i = 0; i < total; i++) {
            insertPending(now.minusHours(1));
        }

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger escalated = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    escalated.addAndGet(
                            outboxMapper.escalatePendingOlderThan(cutoff, 100, now, REASON).size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "escalation threads must finish");

        assertEquals(total, escalated.get(), "concurrent instances must escalate each record at most once");
        assertTrue(outboxMapper.escalatePendingOlderThan(cutoff, 100, now, REASON).isEmpty(),
                "no record may ever be escalated twice");
        insertedIds.forEach(id -> assertNotNull(reload(id).getEscalatedAt()));
    }

    @Test
    void escalate_keepsStatusPendingAndNeverTouchesFrozenOrRetryFields() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusMinutes(30);
        IotMqOutbox overdue = insertPending(now.minusHours(1));
        String frozenJson = overdue.getRawEnvelopeJson();
        OffsetDateTime frozenNextRetryAt = overdue.getNextRetryAt();

        outboxMapper.escalatePendingOlderThan(cutoff, 100, now, REASON);

        IotMqOutbox reloaded = reload(overdue);
        assertEquals(OutboxStatus.PENDING.getCode(), reloaded.getStatus());
        assertEquals(frozenJson, reloaded.getRawEnvelopeJson(), "rawEnvelopeJson must stay frozen");
        assertTrue(Duration.between(frozenNextRetryAt.toInstant(), reloaded.getNextRetryAt().toInstant()).abs()
                        .compareTo(Duration.ofMillis(1)) <= 0,
                "next_retry_at must not be touched: " + frozenNextRetryAt + " vs " + reloaded.getNextRetryAt());
        assertNull(reloaded.getLeaseExpireAt(), "lease must not be touched");
        assertNull(reloaded.getClaimedBy());
        assertEquals(REASON, reloaded.getEscalationReason());
    }

    @Test
    void escalatedRecord_isStillClaimableByRetryTask() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusMinutes(30);
        IotMqOutbox overdue = insertPending(now.minusHours(1));
        outboxMapper.escalatePendingOlderThan(cutoff, 100, now, REASON);

        OffsetDateTime claimNow = OffsetDateTime.now();
        List<IotMqOutbox> claimed = outboxMapper.claimPendingRecords(10, claimNow,
                claimNow.plusMinutes(5), "escalation-test-instance");

        assertEquals(1, claimed.size());
        assertEquals(overdue.getEventId(), claimed.get(0).getEventId());
        assertEquals(OutboxStatus.PENDING.getCode(), claimed.get(0).getStatus());
    }

    @Test
    void escalatedRecord_brokerRecoverySendsOriginalEnvelopeAndReachesSentKeepingAudit() throws Exception {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime cutoff = now.minusMinutes(30);
        IotMqOutbox overdue = insertPending(now.minusHours(1));
        String frozenJson = overdue.getRawEnvelopeJson();
        outboxMapper.escalatePendingOlderThan(cutoff, 100, now, REASON);

        OffsetDateTime claimNow = OffsetDateTime.now();
        List<IotMqOutbox> claimed = outboxMapper.claimPendingRecords(10, claimNow,
                claimNow.plusMinutes(5), "recovery-instance");
        assertEquals(1, claimed.size());
        IotMqOutbox escalated = claimed.get(0);
        assertEquals(OutboxStatus.PENDING.getCode(), escalated.getStatus());

        sosEventProducer.send(escalated);

        org.mockito.ArgumentCaptor<Message<String>> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("elder-sos-event:SOS"), captor.capture(), eq(3000L));
        assertEquals(frozenJson, captor.getValue().getPayload(), "recovery must send the original envelope byte-for-byte");

        IotMqOutbox sent = reload(overdue);
        assertEquals(OutboxStatus.SENT.getCode(), sent.getStatus());
        assertNotNull(sent.getSentAt());
        assertEquals(frozenJson, sent.getRawEnvelopeJson(), "envelope must stay frozen after SENT");
        assertNotNull(sent.getEscalatedAt(), "escalated_at must be retained for audit after SENT");
        assertEquals(REASON, sent.getEscalationReason());
    }

    @Test
    void c16_2Cleanup_neverDeletesEscalatedPendingRecords() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusMinutes(30);
        IotMqOutbox overdue = insertPending(now.minusHours(1));
        outboxMapper.escalatePendingOlderThan(cutoff, 100, now, REASON);

        // C16-2 deletes only status='SENT'; a far-future cutoff may delete old SENT
        // rows left by other tests, but escalated PENDING must always survive.
        outboxMapper.deleteSentOlderThan(now.plusDays(1), 100);

        IotMqOutbox reloaded = reload(overdue);
        assertEquals(OutboxStatus.PENDING.getCode(), reloaded.getStatus());
        assertNotNull(reloaded.getEscalatedAt());
    }

    @Test
    void migration_createsPartialPendingEscalationIndex() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'iot_mq_outbox'");
             ResultSet rs = ps.executeQuery()) {
            boolean found = false;
            while (rs.next()) {
                String name = rs.getString("indexname");
                String def = rs.getString("indexdef");
                if ("idx_outbox_pending_escalation".equals(name)
                        && def.contains("(created_at, id)")
                        && def.contains("'PENDING'::text")
                        && def.contains("escalated_at IS NULL")) {
                    found = true;
                }
            }
            assertTrue(found, "partial index idx_outbox_pending_escalation missing");
        }
    }

    private IotMqOutbox insertPending(OffsetDateTime createdAt) {
        String eventId = "EVT-" + UUID.randomUUID();
        String deviceId = "DEV-" + UUID.randomUUID();
        String sourceMessageId = "MSG-" + UUID.randomUUID();
        String traceId = "trace-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", deviceId);
        payload.put("deviceType", "SOS_BUTTON");

        Map<String, Object> rawEnvelope = new LinkedHashMap<>();
        rawEnvelope.put("eventId", eventId);
        rawEnvelope.put("eventType", "SOS_TRIGGERED");
        rawEnvelope.put("schemaVersion", 1);
        rawEnvelope.put("occurredAt", createdAt.toInstant().toString());
        rawEnvelope.put("traceId", traceId);
        rawEnvelope.put("producer", "service-iot");
        rawEnvelope.put("payload", payload);

        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setId(IdWorker.getId());
        outbox.setEventId(eventId);
        outbox.setDeviceId(deviceId);
        outbox.setSourceMessageId(sourceMessageId);
        outbox.setEventType("SOS_TRIGGERED");
        outbox.setTopic(MqTopicConstants.SOS_EVENT_TOPIC);
        outbox.setTag(MqTopicConstants.TAG_SOS);
        outbox.setPayload(payload);
        outbox.setRawEnvelope(rawEnvelope);
        try {
            outbox.setRawEnvelopeJson(new ObjectMapper().writeValueAsString(rawEnvelope));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(0);
        outbox.setCreatedAt(createdAt);
        outbox.setNextRetryAt(OffsetDateTime.now());

        assertEquals(1, outboxMapper.insertOnConflict(outbox));
        insertedIds.add(outbox.getId());
        return outbox;
    }

    private IotMqOutbox reload(IotMqOutbox outbox) {
        return reload(outbox.getId());
    }

    private IotMqOutbox reload(Long id) {
        return outboxMapper.selectById(id);
    }
}