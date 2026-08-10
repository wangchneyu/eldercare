package com.eldercare.iot.mq;

import com.eldercare.iot.enums.OnlineStatus;
import com.eldercare.iot.heartbeat.DeviceStatusEvent;
import com.eldercare.iot.metrics.IotMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked") // Mockito's Message.class captor cannot retain the payload generic at runtime.
class DeviceStatusProducerTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private IotMetrics metrics;

    private DeviceStatusProducer producer;

    @BeforeEach
    void setUp() {
        producer = new DeviceStatusProducer(
                rocketMQTemplate,
                new ObjectMapper(),
                Runnable::run,
                metrics,
                MqTopicConstants.DEVICE_STATUS_TOPIC
        );
    }

    @Test
    void publish_sendsTheExactFrozenSixFieldSchemaWithTraceHeader() throws Exception {
        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(2, SendCallback.class);
            callback.onSuccess(sendResult());
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());

        producer.publish(event());

        ArgumentCaptor<Message<String>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).asyncSend(eq(MqTopicConstants.DEVICE_STATUS_TOPIC), messageCaptor.capture(),
                any(SendCallback.class), eq(3_000L));
        Map<String, Object> payload = new ObjectMapper().readValue(messageCaptor.getValue().getPayload(),
                new TypeReference<>() {
                });
        assertEquals(Set.of("deviceId", "deviceType", "oldStatus", "newStatus", "occurredAt", "traceId"),
                payload.keySet());
        assertEquals("DEV-001", payload.get("deviceId"));
        assertEquals("RADAR", payload.get("deviceType"));
        assertEquals("ONLINE", payload.get("oldStatus"));
        assertEquals("OFFLINE", payload.get("newStatus"));
        assertEquals("2026-08-10T02:00:00Z", payload.get("occurredAt"));
        assertEquals("trace-status-1", payload.get("traceId"));
        assertEquals("trace-status-1", messageCaptor.getValue().getHeaders().get(DeviceStatusProducer.TRACE_ID_HEADER));
        assertFalse(payload.containsKey("eventId"));
        assertFalse(payload.containsKey("eventType"));
        assertFalse(payload.containsKey("payload"));
        verify(metrics).mqSent(MqTopicConstants.DEVICE_STATUS_TOPIC, DeviceStatusProducer.METRIC_TAG);
    }

    @Test
    void publish_sendFailureIsRecordedWithoutRetryOrOutbox() {
        doAnswer(invocation -> {
            SendCallback callback = invocation.getArgument(2, SendCallback.class);
            callback.onException(new RuntimeException("broker unavailable"));
            return null;
        }).when(rocketMQTemplate).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());

        producer.publish(event());

        verify(rocketMQTemplate).asyncSend(eq(MqTopicConstants.DEVICE_STATUS_TOPIC), any(Message.class),
                any(SendCallback.class), eq(3_000L));
        verify(metrics).mqFailed(MqTopicConstants.DEVICE_STATUS_TOPIC, DeviceStatusProducer.METRIC_TAG, "send_failure");
    }

    @Test
    void publish_rejectedExecutorDoesNotCallRocketMq() {
        DeviceStatusProducer rejectedProducer = new DeviceStatusProducer(
                rocketMQTemplate,
                new ObjectMapper(),
                command -> {
                    throw new RejectedExecutionException("full");
                },
                metrics,
                MqTopicConstants.DEVICE_STATUS_TOPIC
        );

        rejectedProducer.publish(event());

        verify(rocketMQTemplate, never()).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
        verify(metrics).mqFailed(MqTopicConstants.DEVICE_STATUS_TOPIC, DeviceStatusProducer.METRIC_TAG,
                "executor_rejected");
    }

    @Test
    void publish_invalidFrozenFieldDoesNotCallRocketMq() {
        DeviceStatusEvent invalid = new DeviceStatusEvent(
                "DEV-001", null, OnlineStatus.ONLINE, OnlineStatus.OFFLINE, "OFFLINE",
                OffsetDateTime.parse("2026-08-10T02:00:00Z"), "trace-status-1");

        producer.publish(invalid);

        verify(rocketMQTemplate, never()).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
        verify(metrics).mqFailed(MqTopicConstants.DEVICE_STATUS_TOPIC, DeviceStatusProducer.METRIC_TAG,
                "serialization_failure");
    }

    private DeviceStatusEvent event() {
        return new DeviceStatusEvent(
                "DEV-001", "RADAR", OnlineStatus.ONLINE, OnlineStatus.OFFLINE, "OFFLINE",
                OffsetDateTime.parse("2026-08-10T02:00:00Z"), "trace-status-1");
    }

    private SendResult sendResult() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        return result;
    }
}
