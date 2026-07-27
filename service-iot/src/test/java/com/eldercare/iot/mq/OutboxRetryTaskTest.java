package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OutboxRetryTask 单元测试：验证 PENDING 扫描、原始 eventId/payload 复用、空 payload 标记 FAILED。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRetryTaskTest {

    @Mock
    IotMqOutboxMapper outboxMapper;
    @Mock
    SosEventProducer sosEventProducer;
    @Mock
    Executor iotP0Executor;

    @InjectMocks
    OutboxRetryTask retryTask;

    @Test
    void retryPending_scansAndDispatchesToP0Executor() {
        IotMqOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotP0Executor).execute(any(Runnable.class));

        retryTask.retryPending();

        verify(outboxMapper).selectList(any());
        verify(sosEventProducer).send(outbox);
    }

    @Test
    void retryPending_preservesOriginalEventIdAndTraceId() {
        IotMqOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
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
    void emptyPayload_isMarkedFailed() {
        IotMqOutbox outbox = pendingOutbox();
        outbox.setPayload(null);
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));

        retryTask.retryPending();

        ArgumentCaptor<IotMqOutbox> captor = ArgumentCaptor.forClass(IotMqOutbox.class);
        verify(outboxMapper).updateById(captor.capture());
        IotMqOutbox updated = captor.getValue();
        assertEquals(OutboxStatus.FAILED.getCode(), updated.getStatus());
        assertEquals("payload 为空，不可恢复", updated.getLastError());
        verify(sosEventProducer, never()).send(any());
        verify(iotP0Executor, never()).execute(any());
    }

    @Test
    void scanException_isLoggedAndIgnored() {
        when(outboxMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        retryTask.retryPending();

        verify(sosEventProducer, never()).send(any());
        verify(iotP0Executor, never()).execute(any());
    }

    @Test
    void noPendingRecords_doesNothing() {
        when(outboxMapper.selectList(any())).thenReturn(List.of());

        retryTask.retryPending();

        verify(iotP0Executor, never()).execute(any());
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
        outbox.setStatus(OutboxStatus.PENDING.getCode());
        outbox.setRetryCount(2);
        outbox.setCreatedAt(OffsetDateTime.now());
        return outbox;
    }
}
