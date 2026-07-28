package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotVitalDeliveryOutbox;
import com.eldercare.iot.mapper.IotVitalDeliveryOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VitalDeliveryRetryTaskTest {

    @Mock
    IotVitalDeliveryOutboxMapper outboxMapper;
    @Mock
    RocketMQTemplate rocketMQTemplate;
    @Mock
    IotMetrics metrics;
    @Mock
    ScheduledExecutorService scheduler;

    private VitalDeliveryRetryTask task;

    @BeforeEach
    void setUp() {
        task = new VitalDeliveryRetryTask(outboxMapper, rocketMQTemplate, metrics, scheduler);
        task.init();
        lenient().when(outboxMapper.countPending()).thenReturn(1L);
    }

    @Test
    void pendingRecord_isResentToOriginalTopicAndMarkedSent() {
        IotVitalDeliveryOutbox outbox = outbox(false);
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), anyString())).thenReturn(List.of(outbox));
        doAnswer(invocation -> {
            SendResult result = new SendResult();
            result.setSendStatus(SendStatus.SEND_OK);
            invocation.getArgument(2, SendCallback.class).onSuccess(result);
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
        when(outboxMapper.markSent(eq("EVT-1"), anyString(), any())).thenReturn(1);

        task.retryPending();

        verify(rocketMQTemplate).asyncSend(eq("elder-vital-raw:MATTRESS"), any(Message.class), any(SendCallback.class), eq(3_000L));
        verify(outboxMapper).markSent(eq("EVT-1"), anyString(), any());
        verify(metrics).mqSent("elder-vital-raw", "MATTRESS");
    }

    @Test
    void expiredRecord_isSentToBusinessIsolationTopicAndMarkedQuarantined() {
        IotVitalDeliveryOutbox outbox = outbox(true);
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), anyString())).thenReturn(List.of(outbox));
        doAnswer(invocation -> {
            SendResult result = new SendResult();
            result.setSendStatus(SendStatus.SEND_OK);
            invocation.getArgument(2, SendCallback.class).onSuccess(result);
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
        when(outboxMapper.markQuarantined(eq("EVT-1"), anyString(), any())).thenReturn(1);

        task.retryPending();

        ArgumentCaptor<Message<String>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).asyncSend(eq("elder-vital-delivery-failed:MATTRESS"), messageCaptor.capture(),
                any(SendCallback.class), eq(3_000L));
        assertEquals("elder-vital-raw", messageCaptor.getValue().getHeaders().get(VitalDeliveryRetryTask.ORIGINAL_TOPIC_HEADER));
        verify(outboxMapper).markQuarantined(eq("EVT-1"), anyString(), any());
        verify(metrics).vitalDeliveryQuarantined("MATTRESS");
    }

    @Test
    void nonSendOkResult_keepsRecordPendingForLaterRetry() {
        IotVitalDeliveryOutbox outbox = outbox(false);
        when(outboxMapper.claimPendingRecords(anyInt(), any(), any(), anyString())).thenReturn(List.of(outbox));
        doAnswer(invocation -> {
            SendResult result = new SendResult();
            result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
            invocation.getArgument(2, SendCallback.class).onSuccess(result);
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
        when(outboxMapper.recordFailure(eq("EVT-1"), anyString(), eq(5), anyString(), any())).thenReturn(1);

        task.retryPending();

        verify(outboxMapper, never()).markSent(anyString(), anyString(), any());
        verify(outboxMapper).recordFailure(eq("EVT-1"), anyString(), eq(5), contains("SEND_OK"), any());
        verify(metrics).vitalDeliveryRetried("MATTRESS");
    }

    private IotVitalDeliveryOutbox outbox(boolean expired) {
        IotVitalDeliveryOutbox outbox = new IotVitalDeliveryOutbox();
        outbox.setEventId("EVT-1");
        outbox.setDeviceType("MATTRESS");
        outbox.setTopic("elder-vital-raw");
        outbox.setTag("MATTRESS");
        outbox.setTraceId("trace-1");
        outbox.setRawEnvelopeJson("{\"eventId\":\"EVT-1\"}");
        outbox.setRetryCount(4);
        outbox.setExpiresAt(expired ? OffsetDateTime.now().minusSeconds(1) : OffsetDateTime.now().plusHours(1));
        return outbox;
    }
}
