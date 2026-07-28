package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.metrics.IotMetrics;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SosEventProducer 单元测试：验证 C05 冻结契约、Tag 区分 SOS/FALL、原始信封复用、前台 3 次重试与状态流转。
 */
@ExtendWith(MockitoExtension.class)
class SosEventProducerTest {

    @Mock
    RocketMQTemplate rocketMQTemplate;
    @Mock
    OutboxService outboxService;
    @Mock
    IotMetrics metrics;
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
        Message<String> message = captor.getValue();
        String json = message.getPayload();
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
        assertFalse(payload.containsKey("occurredAt"), "occurredAt 已在信封顶层");
        assertFalse(payload.containsKey("traceId"), "traceId 已在信封顶层");

        // Header 中必须包含 X-Trace-Id
        assertEquals("trace-1", message.getHeaders().get(SosEventProducer.TRACE_ID_HEADER));
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
        when(outboxService.markSent("EVT-001")).thenReturn(true);

        producer.send(outbox);

        verify(outboxService).markSent("EVT-001");
        verify(outboxService, never()).recordFailure(anyString(), anyInt(), anyString());
        verify(metrics).mqSent(eq(MqTopicConstants.SOS_EVENT_TOPIC), eq(MqTopicConstants.TAG_SOS));
    }

    @Test
    void sendFailureThreeTimes_recordsFailureAndKeepsPending() {
        IotMqOutbox outbox = outbox("SOS_TRIGGERED", MqTopicConstants.TAG_SOS);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenThrow(new RuntimeException("mq down"));

        producer.send(outbox);

        verify(rocketMQTemplate, times(3)).syncSend(anyString(), any(Message.class), eq(3000L));
        verify(outboxService, never()).markSent(anyString());
        verify(outboxService).recordFailure(eq("EVT-001"), eq(3), contains("前台重试 3 次失败"));
        verify(metrics).mqFailed(eq(MqTopicConstants.SOS_EVENT_TOPIC), eq(MqTopicConstants.TAG_SOS), anyString());
    }

    @Test
    void nonSendOkResult_recordsFailureAndKeepsPending() {
        IotMqOutbox outbox = outbox("SOS_TRIGGERED", MqTopicConstants.TAG_SOS);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenReturn(sendResult(SendStatus.FLUSH_DISK_TIMEOUT));

        producer.send(outbox);

        verify(outboxService, never()).markSent(anyString());
        verify(outboxService).recordFailure(eq("EVT-001"), eq(3), contains("前台重试 3 次失败"));
    }

    @Test
    void serializationFailure_recordsFailureWithoutSending() {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-BAD");
        outbox.setRawEnvelope(null);
        outbox.setPayload(null);
        outbox.setTopic(MqTopicConstants.SOS_EVENT_TOPIC);
        outbox.setTag(MqTopicConstants.TAG_SOS);
        outbox.setRetryCount(0);

        producer.send(outbox);

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class), anyLong());
        verify(outboxService).markFailed(eq("EVT-BAD"), contains("Outbox rawEnvelopeJson 为空"));
    }

    @Test
    void firstSendAndRetry_areByteLevelEquivalent() throws Exception {
        IotMqOutbox outbox = outbox("SOS_TRIGGERED", MqTopicConstants.TAG_SOS);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong()))
                .thenReturn(sendResult(SendStatus.SEND_OK));

        // 首次发送
        producer.send(outbox);
        ArgumentCaptor<Message<String>> firstCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("elder-sos-event:SOS"), firstCaptor.capture(), eq(3000L));
        String firstJson = firstCaptor.getValue().getPayload();

        // 补发（同一 Outbox 记录）
        producer.send(outbox);
        ArgumentCaptor<Message<String>> secondCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate, times(2)).syncSend(eq("elder-sos-event:SOS"), secondCaptor.capture(), eq(3000L));
        String secondJson = secondCaptor.getValue().getPayload();

        // JSON 语义等价（不比较字节，因 LinkedHashMap 顺序一致，实际序列化字节也一致）
        assertEquals(firstJson, secondJson);
        assertEquals(outbox.getRawEnvelopeJson(), firstJson);
    }

    private IotMqOutbox outbox(String eventType, String tag) {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-001");
        outbox.setDeviceId("DEV-001");
        outbox.setSourceMessageId("msg-1");
        outbox.setEventType(eventType);
        outbox.setTopic(MqTopicConstants.SOS_EVENT_TOPIC);
        outbox.setTag(tag);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sourceMessageId", "msg-1");
        payload.put("deviceId", "DEV-001");
        payload.put("deviceType", "SOS_BUTTON");
        payload.put("location", Map.of("locationId", "LOC-001"));
        outbox.setPayload(payload);
        outbox.setRawEnvelope(Map.of(
                "eventId", "EVT-001",
                "eventType", eventType,
                "schemaVersion", 1,
                "occurredAt", "2026-07-24T02:30:00Z",
                "traceId", "trace-1",
                "producer", MqTopicConstants.PRODUCER,
                "payload", payload
        ));
        outbox.setRawEnvelopeJson("""
                {"eventId":"EVT-001","eventType":"%s","schemaVersion":1,"occurredAt":"2026-07-24T02:30:00Z","traceId":"trace-1","producer":"service-iot@1.0.0","payload":{"sourceMessageId":"msg-1","deviceId":"DEV-001","deviceType":"SOS_BUTTON","location":{"locationId":"LOC-001"}}}
                """.strip().formatted(eventType));
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
