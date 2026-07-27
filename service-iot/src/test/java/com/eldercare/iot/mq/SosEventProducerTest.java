package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotMqOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SosEventProducer 单元测试：验证 C05 冻结契约、Tag 区分 SOS/FALL、前台 3 次重试与状态流转。
 */
@ExtendWith(MockitoExtension.class)
class SosEventProducerTest {

    @Mock
    RocketMQTemplate rocketMQTemplate;
    @Mock
    OutboxService outboxService;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    SosEventProducer producer;

    @Test
    void c05Envelope_containsRequiredFields_andTopLevelOccurredAt() throws Exception {
        IotMqOutbox outbox = outbox("SOS_TRIGGERED", MqTopicConstants.TAG_SOS);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenReturn(sendResult(SendStatus.SEND_OK));

        producer.send(outbox);

        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("elder-sos-event:SOS"), captor.capture(), eq(3000L));
        String json = captor.getValue().getPayload();
        Map<String, Object> envelope = objectMapper.readValue(json, Map.class);

        assertEquals("EVT-001", envelope.get("eventId"));
        assertEquals("SOS_TRIGGERED", envelope.get("eventType"));
        assertEquals(1, envelope.get("schemaVersion"));
        assertEquals("trace-1", envelope.get("traceId"));
        assertEquals(MqTopicConstants.PRODUCER, envelope.get("producer"));
        assertTrue(envelope.containsKey("occurredAt"));

        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertEquals("DEV-001", payload.get("deviceId"));
        assertEquals("LOC-001", ((Map<String, Object>) payload.get("location")).get("locationId"));
        assertFalse(payload.containsKey("occurredAt"), "occurredAt 应被提升到信封顶层");
        assertFalse(payload.containsKey("traceId"), "traceId 应被提升到信封顶层");
    }

    @Test
    void fallEvent_usesFallTag() {
        IotMqOutbox outbox = outbox("FALL_DETECTED", MqTopicConstants.TAG_FALL);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenReturn(sendResult(SendStatus.SEND_OK));

        producer.send(outbox);

        verify(rocketMQTemplate).syncSend(eq("elder-sos-event:FALL"), any(Message.class), eq(3000L));
        verify(outboxService).markSent("EVT-001");
    }

    @Test
    void sendSuccess_updatesOutboxSent() {
        IotMqOutbox outbox = outbox("SOS_TRIGGERED", MqTopicConstants.TAG_SOS);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenReturn(sendResult(SendStatus.SEND_OK));

        producer.send(outbox);

        verify(outboxService).markSent("EVT-001");
        verify(outboxService, never()).recordFailure(any(), any());
    }

    @Test
    void sendFailureThreeTimes_recordsFailureAndKeepsPending() {
        IotMqOutbox outbox = outbox("SOS_TRIGGERED", MqTopicConstants.TAG_SOS);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenThrow(new RuntimeException("mq down"));

        producer.send(outbox);

        verify(rocketMQTemplate, times(3)).syncSend(anyString(), any(Message.class), eq(3000L));
        verify(outboxService, never()).markSent(any());
        verify(outboxService).recordFailure(eq("EVT-001"), contains("前台重试 3 次失败"));
    }

    @Test
    void nonSendOkResult_recordsFailureAndKeepsPending() {
        IotMqOutbox outbox = outbox("SOS_TRIGGERED", MqTopicConstants.TAG_SOS);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenReturn(sendResult(SendStatus.FLUSH_DISK_TIMEOUT));

        producer.send(outbox);

        verify(outboxService, never()).markSent(any());
        verify(outboxService).recordFailure(eq("EVT-001"), contains("前台重试 3 次失败"));
    }

    @Test
    void serializationFailure_recordsFailureWithoutSending() {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-BAD");
        outbox.setPayload(null);

        producer.send(outbox);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class), anyLong());
        verify(outboxService).recordFailure(eq("EVT-BAD"), contains("Outbox payload 为空"));
    }

    private IotMqOutbox outbox(String eventType, String tag) {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-001");
        outbox.setDeviceId("DEV-001");
        outbox.setSourceMessageId("msg-1");
        outbox.setEventType(eventType);
        outbox.setTopic(MqTopicConstants.SOS_EVENT_TOPIC);
        outbox.setTag(tag);
        outbox.setPayload(Map.of(
                "sourceMessageId", "msg-1",
                "deviceId", "DEV-001",
                "deviceType", "SOS_BUTTON",
                "location", Map.of("locationId", "LOC-001"),
                "occurredAt", "2026-07-24T02:30:00Z",
                "traceId", "trace-1"
        ));
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(OffsetDateTime.now());
        return outbox;
    }

    private SendResult sendResult(SendStatus status) {
        SendResult result = new SendResult();
        result.setSendStatus(status);
        return result;
    }
}
