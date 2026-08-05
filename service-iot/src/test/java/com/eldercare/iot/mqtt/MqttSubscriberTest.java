package com.eldercare.iot.mqtt;

import com.eldercare.iot.metrics.IotMetrics;
import com.eldercare.iot.parser.model.RawDeviceMessage;
import com.eldercare.iot.pipeline.DisruptorPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MqttSubscriber 单元测试：验证 Topic 路由、格式校验、信封元数据提取、eventId 生成与 Disruptor 非阻塞投递。
 */
@ExtendWith(MockitoExtension.class)
class MqttSubscriberTest {

    @Mock
    MqttConnectionManager connectionManager;
    @Mock
    TopicRouter topicRouter;
    @Mock
    MessageValidator messageValidator;
    @Mock
    DisruptorPublisher disruptorPublisher;
    @Mock
    IotMetrics metrics;
    @Spy
    MqttConfig mqttConfig = new MqttConfig();

    @InjectMocks
    MqttSubscriber subscriber;

    @BeforeEach
    void widenPastWindowForFixedContractFixtures() {
        mqttConfig.setMaxPastSeconds(1_000_000);
    }

    @Test
    void initRegistersMessageHandlerAndConnects() {
        subscriber.init();
        verify(connectionManager).setMessageHandler(any(MqttConnectionManager.MessageHandler.class));
        verify(connectionManager).connect();
    }

    @Test
    void validMessage_isPublishedToDisruptor() throws Exception {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        String payload = """
                {
                    "messageId": "msg-001",
                    "deviceId": "DEV-001",
                    "messageType": "VITAL_SIGN",
                    "protocolVersion": "1.0",
                    "occurredAt": "%s",
                    "payload": { "heart_rate": 75 }
                }
                """.formatted(validOccurredAt());
        JsonNode envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.of(envelope));
        when(disruptorPublisher.publish(any(RawDeviceMessage.class))).thenReturn(true);

        dispatch(topic, payload, 1, 1);

        ArgumentCaptor<RawDeviceMessage> captor = ArgumentCaptor.forClass(RawDeviceMessage.class);
        verify(disruptorPublisher).publish(captor.capture());
        RawDeviceMessage raw = captor.getValue();
        assertEquals(topic, raw.topic());
        assertEquals("P001", raw.parkId());
        assertEquals("MATTRESS", raw.deviceType());
        assertEquals("DEV-001", raw.deviceId());
        assertEquals("VITAL_SIGN", raw.messageType());
        assertNotNull(raw.eventId());
        assertTrue(raw.eventId().matches("\\d+"), "eventId 应为雪花 ID 十进制字符串");
        assertNotNull(raw.traceId());
        assertEquals(1, raw.mqttMessageId());
        assertEquals(1, raw.mqttQos());
    }

    @Test
    void invalidTopic_isNotPublished() {
        String topic = "bad/topic";
        when(topicRouter.route(topic)).thenReturn(Optional.empty());

        assertEquals(1, dispatch(topic, "{}", 1, 1));

        verify(disruptorPublisher, never()).publish(any());
    }

    @Test
    void invalidPayload_isNotPublished() {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.empty());

        assertEquals(1, dispatch(topic, "{}", 1, 1));

        verify(disruptorPublisher, never()).publish(any());
    }

    @Test
    void deviceIdMismatch_isRejected() {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        String payload = """
                {
                    "messageId": "msg-001",
                    "deviceId": "DEV-999",
                    "messageType": "VITAL_SIGN",
                    "protocolVersion": "1.0",
                    "occurredAt": "%s"
                }
                """.formatted(validOccurredAt());
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        JsonNode envelope;
        try {
            envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.of(envelope));

        assertEquals(1, dispatch(topic, payload, 1, 1));

        verify(disruptorPublisher, never()).publish(any());
        verify(metrics).mqttMessageRejected("device_id_mismatch");
    }

    @Test
    void invalidOccurredAt_isRejected() {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        String payload = """
                {
                    "messageId": "msg-001",
                    "deviceId": "DEV-001",
                    "messageType": "VITAL_SIGN",
                    "protocolVersion": "1.0",
                    "occurredAt": "not-a-timestamp"
                }
                """;
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        JsonNode envelope;
        try {
            envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.of(envelope));

        assertEquals(1, dispatch(topic, payload, 1, 1));

        verify(disruptorPublisher, never()).publish(any());
        verify(metrics).mqttMessageRejected("invalid_occurred_at");
    }

    @Test
    void topicAndEnvelopeMessageTypeMismatch_isRejected() throws Exception {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        JsonNode envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"messageId":"msg-1","deviceId":"DEV-001","messageType":"HEARTBEAT",
                 "protocolVersion":"1.0","occurredAt":"2026-07-24T02:30:00Z"}
                """);
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.of(envelope));

        assertEquals(1, dispatch(topic, envelope.toString(), 1, 1));

        verify(disruptorPublisher, never()).publish(any());
        verify(metrics).mqttMessageRejected("topic_message_type_mismatch");
    }

    @Test
    void staleOccurredAt_isRejected() throws Exception {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        JsonNode envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"messageId":"msg-1","deviceId":"DEV-001","messageType":"VITAL_SIGN",
                 "protocolVersion":"1.0","occurredAt":"2020-01-01T00:00:00Z"}
                """);
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.of(envelope));

        assertEquals(1, dispatch(topic, envelope.toString(), 1, 1));

        verify(disruptorPublisher, never()).publish(any());
        verify(metrics).mqttMessageRejected("occurred_at_out_of_range");
    }

    @Test
    void ringBufferFull_doesNotBlockAndRejects() {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        String payload = """
                {
                    "messageId": "msg-001",
                    "deviceId": "DEV-001",
                    "messageType": "VITAL_SIGN",
                    "protocolVersion": "1.0",
                    "occurredAt": "%s"
                }
                """.formatted(validOccurredAt());
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        JsonNode envelope;
        try {
            envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.of(envelope));
        when(disruptorPublisher.publish(any(RawDeviceMessage.class))).thenReturn(false);

        assertEquals(0, dispatch(topic, payload, 1, 1));

        verify(disruptorPublisher).publish(any(RawDeviceMessage.class));
        verify(metrics).ringBufferRejected();
    }

    @Test
    void destroyDisconnects() {
        subscriber.destroy();
        verify(connectionManager).disconnect();
    }

    private int dispatch(String topic, String payload, int messageId, int qos) {
        ArgumentCaptor<MqttConnectionManager.MessageHandler> captor = ArgumentCaptor.forClass(MqttConnectionManager.MessageHandler.class);
        subscriber.init();
        verify(connectionManager).setMessageHandler(captor.capture());
        AtomicInteger acknowledgements = new AtomicInteger();
        InboundMqttMessage inbound = new InboundMqttMessage(
                topic, payload.getBytes(StandardCharsets.UTF_8), messageId, qos, msg -> acknowledgements.incrementAndGet());
        captor.getValue().handle(inbound);
        return acknowledgements.get();
    }

    private static String validOccurredAt() {
        return OffsetDateTime.now().minusSeconds(1).toString();
    }
}
