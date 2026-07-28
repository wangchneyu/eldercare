package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.eldercare.iot.mqtt.InboundMqttMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OutboxService 单元测试：验证 C05 事务写入、原始信封持久化、ON CONFLICT 去重、条件更新与 traceId 保留。
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    IotMqOutboxMapper outboxMapper;
    @Mock
    SosEventProducer sosEventProducer;
    @Mock
    PlatformTransactionManager transactionManager;
    @Mock
    IotMetrics metrics;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    OutboxService outboxService;

    @Test
    void handleSosEvent_savesAndSends() {
        stubTransactionManager();
        ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
        when(outboxMapper.insertOnConflict(any(IotMqOutbox.class))).thenReturn(1);

        outboxService.handleSosEvent(event);

        verify(outboxMapper).insertOnConflict(any(IotMqOutbox.class));
        verify(sosEventProducer).send(any(IotMqOutbox.class));
    }

    @Test
    void duplicateEvent_isIgnoredAndNotSent() {
        stubTransactionManager();
        ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
        when(outboxMapper.insertOnConflict(any(IotMqOutbox.class))).thenReturn(0);

        outboxService.handleSosEvent(event);

        verify(sosEventProducer, never()).send(any());
        verify(metrics).outboxDuplicate("SOS_TRIGGERED");
    }

    @Test
    void duplicateEvent_isAcknowledgedAfterExistingOutboxIsConfirmed() {
        stubTransactionManager();
        when(outboxMapper.insertOnConflict(any(IotMqOutbox.class))).thenReturn(0);
        AtomicInteger acknowledgements = new AtomicInteger();
        InboundMqttMessage inbound = new InboundMqttMessage("topic", null, 1, 1,
                message -> acknowledgements.incrementAndGet());

        outboxService.handleSosEvent(sosEvent("SOS_TRIGGERED"), inbound);

        assertEquals(1, acknowledgements.get());
        verify(sosEventProducer, never()).send(any());
    }

    @Test
    void fallEvent_usesFallTag() {
        stubTransactionManager();
        ParsedSosEvent event = sosEvent("FALL_DETECTED");
        when(outboxMapper.insertOnConflict(any(IotMqOutbox.class))).thenReturn(1);

        outboxService.handleSosEvent(event);

        ArgumentCaptor<IotMqOutbox> captor = ArgumentCaptor.forClass(IotMqOutbox.class);
        verify(outboxMapper).insertOnConflict(captor.capture());
        assertEquals(MqTopicConstants.TAG_FALL, captor.getValue().getTag());
        assertEquals("FALL_DETECTED", captor.getValue().getEventType());
        verify(sosEventProducer).send(any(IotMqOutbox.class));
    }

    @Test
    void buildOutbox_preservesTraceIdForRetry() {
        stubTransactionManager();
        TraceContext.setTraceId("trace-retry");
        try {
            ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
            when(outboxMapper.insertOnConflict(any(IotMqOutbox.class))).thenReturn(1);

            outboxService.handleSosEvent(event);

            ArgumentCaptor<IotMqOutbox> captor = ArgumentCaptor.forClass(IotMqOutbox.class);
            verify(outboxMapper).insertOnConflict(captor.capture());
            IotMqOutbox outbox = captor.getValue();
            assertNotNull(outbox.getRawEnvelope());
            assertNotNull(outbox.getRawEnvelopeJson());
            assertEquals("trace-retry", outbox.getRawEnvelope().get("traceId"));
            // C05 元数据字段只在信封顶层，payload 中不再冗余
            assertFalse(outbox.getPayload().containsKey("traceId"));
            assertFalse(outbox.getPayload().containsKey("occurredAt"));
        } finally {
            TraceContext.clear();
        }
    }

    @Test
    void markSent_updatesStatusConditionally() {
        when(outboxMapper.updateStatusConditionally(eq("EVT-1"), eq(OutboxStatus.SENT.getCode()),
                eq(OutboxStatus.PENDING.getCode()), any(OffsetDateTime.class))).thenReturn(1);

        assertTrue(outboxService.markSent("EVT-1"));
    }

    @Test
    void markSent_conflict_reportsMetric() {
        when(outboxMapper.updateStatusConditionally(eq("EVT-1"), eq(OutboxStatus.SENT.getCode()),
                eq(OutboxStatus.PENDING.getCode()), any(OffsetDateTime.class))).thenReturn(0);

        assertFalse(outboxService.markSent("EVT-1"));
        verify(metrics).outboxStatusConflict("EVT-1", OutboxStatus.PENDING.getCode(), "unknown");
    }

    @Test
    void recordFailure_incrementsRetryCount_andKeepsPending() {
        when(outboxMapper.updateFailureConditionally("EVT-1", OutboxStatus.PENDING.getCode(),
                OutboxStatus.PENDING.getCode(), 3, "mq timeout")).thenReturn(1);

        assertTrue(outboxService.recordFailure("EVT-1", 3, "mq timeout"));
    }

    @Test
    void unknownEventType_rejects() {
        ParsedSosEvent event = sosEvent("UNKNOWN_EVENT");

        assertThrows(IllegalArgumentException.class, () -> outboxService.handleSosEvent(event));
        verify(outboxMapper, never()).insertOnConflict(any());
        verify(transactionManager, never()).getTransaction(any());
    }

    private ParsedSosEvent sosEvent(String eventType) {
        return new ParsedSosEvent(
                "EVT-001", "msg-1", "DEV-001", OffsetDateTime.now(), "trace-1", "P001", "SOS_BUTTON",
                eventType, 123L, "BIND-ELDER", "B001", "R001", "301", "BIND-LOC",
                Map.of("locationId", "LOC-001"), "BUTTON_PRESS", 85, null);
    }

    private void stubTransactionManager() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactionManager).commit(any(TransactionStatus.class));
    }
}
