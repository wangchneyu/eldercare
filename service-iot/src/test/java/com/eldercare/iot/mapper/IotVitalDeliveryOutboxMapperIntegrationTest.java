package com.eldercare.iot.mapper;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.entity.IotVitalDeliveryOutbox;
import com.eldercare.iot.enums.VitalDeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL integration coverage for the C04 failure-only lease protocol. */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
class IotVitalDeliveryOutboxMapperIntegrationTest {

    @Autowired
    private IotVitalDeliveryOutboxMapper outboxMapper;

    @Test
    void insertClaimAndMarkSent_areAtomicAndIdempotent() {
        IotVitalDeliveryOutbox outbox = newOutbox("EVT-" + UUID.randomUUID());
        assertEquals(1, outboxMapper.insertOnConflict(outbox));

        IotVitalDeliveryOutbox duplicate = newOutbox(outbox.getEventId());
        assertEquals(0, outboxMapper.insertOnConflict(duplicate));

        OffsetDateTime now = OffsetDateTime.now();
        List<IotVitalDeliveryOutbox> claimed = outboxMapper.claimPendingRecords(
                10, now, now.plusMinutes(5), "mapper-test");
        assertEquals(1, claimed.size());
        assertEquals(outbox.getEventId(), claimed.get(0).getEventId());
        assertEquals("mapper-test", claimed.get(0).getClaimedBy());

        assertEquals(1, outboxMapper.markSent(outbox.getEventId(), "mapper-test", OffsetDateTime.now()));
        assertEquals(0, outboxMapper.markSent(outbox.getEventId(), "mapper-test", OffsetDateTime.now()));
        assertEquals(VitalDeliveryStatus.SENT.getCode(), outboxMapper.selectById(outbox.getId()).getStatus());

        outboxMapper.deleteById(outbox.getId());
    }

    @Test
    void expiredLeaseCanBeClaimedByAnotherInstance() {
        IotVitalDeliveryOutbox outbox = newOutbox("EVT-" + UUID.randomUUID());
        assertEquals(1, outboxMapper.insertOnConflict(outbox));
        OffsetDateTime now = OffsetDateTime.now();
        List<IotVitalDeliveryOutbox> firstClaim = outboxMapper.claimPendingRecords(
                10, now, now.minusSeconds(1), "crashed-instance");
        assertEquals(1, firstClaim.size());

        List<IotVitalDeliveryOutbox> recovered = outboxMapper.claimPendingRecords(
                10, now, now.plusMinutes(5), "recovery-instance");
        assertEquals(1, recovered.size());
        assertEquals("recovery-instance", recovered.get(0).getClaimedBy());

        outboxMapper.deleteById(outbox.getId());
    }

    @Test
    void terminalRecords_areRemovedOnlyAfterRetentionWindow() {
        IotVitalDeliveryOutbox outbox = newOutbox("EVT-" + UUID.randomUUID());
        assertEquals(1, outboxMapper.insertOnConflict(outbox));
        OffsetDateTime now = OffsetDateTime.now();
        assertEquals(1, outboxMapper.claimPendingRecords(10, now, now.plusMinutes(5), "cleanup-test").size());
        assertEquals(1, outboxMapper.markSent(outbox.getEventId(), "cleanup-test", now.minusHours(8)));

        assertEquals(0, outboxMapper.deleteTerminalBefore(now.minusHours(24)));
        assertEquals(1, outboxMapper.deleteTerminalBefore(now.minusHours(1)));
    }

    private IotVitalDeliveryOutbox newOutbox(String eventId) {
        OffsetDateTime now = OffsetDateTime.now();
        IotVitalDeliveryOutbox outbox = new IotVitalDeliveryOutbox();
        outbox.setId(IdWorker.getId());
        outbox.setEventId(eventId);
        outbox.setDeviceId("DEV-" + UUID.randomUUID());
        outbox.setSourceMessageId("MSG-" + UUID.randomUUID());
        outbox.setDeviceType("MATTRESS");
        outbox.setTopic("elder-vital-raw");
        outbox.setTag("MATTRESS");
        outbox.setTraceId("trace-" + UUID.randomUUID());
        outbox.setRawEnvelopeJson("{\"eventId\":\"" + eventId + "\"}");
        outbox.setStatus(VitalDeliveryStatus.PENDING.getCode());
        outbox.setRetryCount(4);
        outbox.setCreatedAt(now);
        outbox.setNextRetryAt(now.minusSeconds(1));
        outbox.setExpiresAt(now.plusHours(24));
        return outbox;
    }
}
