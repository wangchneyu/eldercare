package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OutboxService 单元测试：验证 C05 事务写入、SOS/FALL Tag、唯一约束去重与 traceId 保留。
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    IotMqOutboxMapper outboxMapper;
    @Mock
    SosEventProducer sosEventProducer;
    @Mock
    PlatformTransactionManager transactionManager;

    @InjectMocks
    OutboxService outboxService;

    @Test
    void handleSosEvent_savesAndSends() {
        stubTransactionManager();
        ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
        when(outboxMapper.insert(any(IotMqOutbox.class))).thenReturn(1);

        outboxService.handleSosEvent(event);

        verify(outboxMapper).insert(any(IotMqOutbox.class));
        verify(sosEventProducer).send(any(IotMqOutbox.class));
    }

    @Test
    void duplicateEvent_isIgnoredAndNotSent() {
        stubTransactionManager();
        ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
        doThrow(DataIntegrityViolationException.class).when(outboxMapper).insert(any(IotMqOutbox.class));

        outboxService.handleSosEvent(event);

        verify(sosEventProducer, never()).send(any());
    }

    @Test
    void fallEvent_usesFallTag() {
        stubTransactionManager();
        ParsedSosEvent event = sosEvent("FALL_DETECTED");
        when(outboxMapper.insert(any(IotMqOutbox.class))).thenReturn(1);

        outboxService.handleSosEvent(event);

        ArgumentCaptor<IotMqOutbox> captor = ArgumentCaptor.forClass(IotMqOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        assertEquals(MqTopicConstants.TAG_FALL, captor.getValue().getTag());
        assertEquals("FALL_DETECTED", captor.getValue().getEventType());
    }

    @Test
    void buildOutbox_preservesTraceIdForRetry() {
        stubTransactionManager();
        TraceContext.setTraceId("trace-retry");
        try {
            ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
            when(outboxMapper.insert(any(IotMqOutbox.class))).thenReturn(1);

            outboxService.handleSosEvent(event);

            ArgumentCaptor<IotMqOutbox> captor = ArgumentCaptor.forClass(IotMqOutbox.class);
            verify(outboxMapper).insert(captor.capture());
            assertEquals("trace-retry", captor.getValue().getPayload().get("traceId"));
        } finally {
            TraceContext.clear();
        }
    }

    @Test
    void markSent_updatesStatus() {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-1");
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        when(outboxMapper.selectOne(any())).thenReturn(outbox);
        when(outboxMapper.updateById(outbox)).thenReturn(1);

        assertTrue(outboxService.markSent("EVT-1"));
        assertEquals(OutboxStatus.SENT.getCode(), outbox.getStatus());
        assertNotNull(outbox.getSentAt());
    }

    @Test
    void recordFailure_incrementsRetryCount_andKeepsPending() {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-1");
        outbox.setRetryCount(2);
        when(outboxMapper.selectOne(any())).thenReturn(outbox);
        when(outboxMapper.updateById(outbox)).thenReturn(1);

        assertTrue(outboxService.recordFailure("EVT-1", "mq timeout"));

        assertEquals(3, outbox.getRetryCount());
        assertEquals("mq timeout", outbox.getLastError());
        assertEquals(OutboxStatus.PENDING.getCode(), outbox.getStatus());
    }

    private ParsedSosEvent sosEvent(String eventType) {
        return new ParsedSosEvent(
                "EVT-001", "msg-1", "DEV-001", OffsetDateTime.now(), "trace-1", "P001", "SOS_BUTTON",
                eventType, 123L, "BIND-ELDER", "B001", "R001", "301", "BIND-LOC",
                Map.of("locationId", "LOC-001"), "BUTTON_PRESS", 85, null);
    }

    /**
     * 提供一个极简的 PlatformTransactionManager 桩，使 TransactionTemplate.execute 同步执行回调。
     */
    private void stubTransactionManager() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactionManager).commit(any(TransactionStatus.class));
        // rollback 在当前测试场景不会触发，不 stub 以避免 UnnecessaryStubbing
    }
}
