package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OutboxRetryTask 单元测试：验证原子 claim/租约、原始 eventId/payload 复用、空 rawEnvelope 标记 FAILED。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRetryTaskTest {

    @Mock
    IotMqOutboxMapper outboxMapper;
    @Mock
    SosEventProducer sosEventProducer;
    @Mock
    Executor iotP0Executor;
    @Mock
    IotMetrics metrics;

    @InjectMocks
    OutboxRetryTask retryTask;

    @BeforeEach
    void init() {
        ReflectionTestUtils.setField(retryTask, "instanceId", "test-instance");
    }

    @Test
    void retryPending_claimsAndDispatchesToP0Executor() {
        IotMqOutbox outbox = pendingOutbox();
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), eq("test-instance")))
                .thenReturn(List.of(outbox));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotP0Executor).execute(any(Runnable.class));

        retryTask.retryPending();

        verify(outboxMapper).claimPendingRecords(anyInt(), any(), any(), eq("test-instance"));
        verify(sosEventProducer).send(outbox);
    }

    @Test
    void retryPending_preservesOriginalEventIdAndTraceId() {
        IotMqOutbox outbox = pendingOutbox();
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), eq("test-instance")))
                .thenReturn(List.of(outbox));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotP0Executor).execute(any(Runnable.class));
        doAnswer(invocation -> {
            assertEquals("trace-retry", TraceContext.currentTraceId());
            assertEquals("EVT-001", invocation.getArgument(0, IotMqOutbox.class).getEventId());
            return null;
        }).when(sosEventProducer).send(any(IotMqOutbox.class));

        retryTask.retryPending();

        verify(sosEventProducer).send(outbox);
    }

    @Test
    void emptyRawEnvelopeJson_isMarkedFailed() {
        IotMqOutbox outbox = pendingOutbox();
        outbox.setRawEnvelopeJson(null);
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), eq("test-instance")))
                .thenReturn(List.of(outbox));

        retryTask.retryPending();

        verify(outboxMapper).updateFailureConditionally(
                eq("EVT-001"),
                eq(OutboxStatus.FAILED.getCode()),
                eq(OutboxStatus.PENDING.getCode()),
                anyInt(),
                eq("rawEnvelopeJson 为空，不可恢复")
        );
        verify(sosEventProducer, never()).send(any());
        verify(iotP0Executor, never()).execute(any());
    }

    @Test
    void claimException_isLoggedAndIgnored() {
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), eq("test-instance")))
                .thenThrow(new RuntimeException("db down"));

        retryTask.retryPending();

        verify(sosEventProducer, never()).send(any());
        verify(iotP0Executor, never()).execute(any());
    }

    @Test
    void noPendingRecords_doesNothing() {
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), eq("test-instance")))
                .thenReturn(List.of());

        retryTask.retryPending();

        verify(iotP0Executor, never()).execute(any());
    }

    @Test
    void sendFailure_releasesLeaseForNextScan() {
        IotMqOutbox outbox = pendingOutbox();
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), eq("test-instance")))
                .thenReturn(List.of(outbox));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotP0Executor).execute(any(Runnable.class));
        doThrow(new RuntimeException("mq down")).when(sosEventProducer).send(any(IotMqOutbox.class));

        retryTask.retryPending();

        verify(outboxMapper).releaseLease("EVT-001", "test-instance");
        assertEquals(OutboxStatus.PENDING.getCode(), outbox.getStatus());
    }

    @Test
    void executorRejection_releasesLeaseForNextScan() {
        IotMqOutbox outbox = pendingOutbox();
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), eq("test-instance")))
                .thenReturn(List.of(outbox));
        doThrow(new RejectedExecutionException("full")).when(iotP0Executor).execute(any(Runnable.class));

        retryTask.retryPending();

        verify(outboxMapper).releaseLease("EVT-001", "test-instance");
        verifyNoInteractions(sosEventProducer);
    }

    private IotMqOutbox pendingOutbox() {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-001");
        outbox.setDeviceId("DEV-001");
        outbox.setSourceMessageId("msg-1");
        outbox.setEventType("SOS_TRIGGERED");
        outbox.setTopic(MqTopicConstants.SOS_EVENT_TOPIC);
        outbox.setTag(MqTopicConstants.TAG_SOS);
        outbox.setPayload(Map.of(
                "deviceId", "DEV-001",
                "traceId", "trace-retry"
        ));
        outbox.setRawEnvelope(Map.of(
                "eventId", "EVT-001",
                "eventType", "SOS_TRIGGERED",
                "traceId", "trace-retry",
                "payload", Map.of("traceId", "trace-retry")
        ));
        outbox.setRawEnvelopeJson("{\"eventId\":\"EVT-001\",\"traceId\":\"trace-retry\"}");
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(2);
        outbox.setCreatedAt(OffsetDateTime.now());
        return outbox;
    }
}
