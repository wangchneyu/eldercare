package com.eldercare.iot.mq;

import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendCallback;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * VitalSignProducer 单元测试：验证 C04 冻结契约字段、异步发送与失败重试调度。
 */
@ExtendWith(MockitoExtension.class)
class VitalSignProducerTest {

    @Mock
    RocketMQTemplate rocketMQTemplate;
    @Mock
    ScheduledExecutorService iotRetryScheduler;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    VitalSignProducer producer;

    @Test
    void c04Envelope_containsRequiredFields_andTagByDeviceType() throws Exception {
        ParsedVitalSign event = new ParsedVitalSign(
                "123456789012345", "msg-1", "DEV-001",
                OffsetDateTime.parse("2026-07-24T02:30:00Z"),
                "trace-1", "P001", "MATTRESS", 42L,
                75, 16, 1, "IN_BED",
                Map.of("extra_key", "extra_value"));

        producer.send(event);

        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).asyncSend(eq("elder-vital-raw:MATTRESS"), captor.capture(),
                any(SendCallback.class), eq(3000L));
        String json = captor.getValue().getPayload();
        Map<String, Object> envelope = objectMapper.readValue(json, Map.class);

        assertEquals("123456789012345", envelope.get("eventId"));
        assertEquals("VITAL_SIGN_REPORTED", envelope.get("eventType"));
        assertEquals(1, envelope.get("schemaVersion"));
        assertEquals("trace-1", envelope.get("traceId"));
        assertEquals(MqTopicConstants.PRODUCER, envelope.get("producer"));

        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertEquals("msg-1", payload.get("sourceMessageId"));
        assertEquals("DEV-001", payload.get("device_id"));
        assertEquals("42", payload.get("elder_id"));
        assertTrue(payload.containsKey("data_time"));
        assertEquals(75, payload.get("heart_rate"));
        assertEquals("extra_value", payload.get("extra_key"));
    }

    @Test
    void asyncSendSuccess_removesRetryCounter() {
        ParsedVitalSign event = vitalSign("EVT-1");
        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(2, SendCallback.class);
            callback.onSuccess(sendResult(SendStatus.SEND_OK));
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());

        producer.send(event);
        producer.send(event); // 第二次不应因旧计数器报错

        verify(rocketMQTemplate, times(2)).asyncSend(anyString(), any(Message.class),
                any(SendCallback.class), anyLong());
    }

    @Test
    void asyncSendFailure_schedulesFirstRetry() {
        ParsedVitalSign event = vitalSign("EVT-1");
        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(2, SendCallback.class);
            callback.onException(new RuntimeException("mq down"));
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());

        producer.send(event);

        verify(iotRetryScheduler).schedule(any(Runnable.class), eq(10_000L), eq(TimeUnit.MILLISECONDS));
    }

    private ParsedVitalSign vitalSign(String eventId) {
        return new ParsedVitalSign(
                eventId, "msg", "DEV", OffsetDateTime.now(), "trace",
                "P001", "MATTRESS", 1L, 75, 16, 1, "IN_BED", null);
    }

    private SendResult sendResult(SendStatus status) {
        SendResult result = new SendResult();
        result.setSendStatus(status);
        return result;
    }
}
