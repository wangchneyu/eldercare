package com.eldercare.iot.mqtt;

import com.eldercare.iot.parser.model.RawDeviceMessage;
import com.eldercare.iot.pipeline.DisruptorPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MqttSubscriber 单元测试：验证 Topic 路由、格式校验、信封元数据提取、eventId 生成与 Disruptor 投递。
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

    @InjectMocks
    MqttSubscriber subscriber;

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
                    "occurredAt": "2026-07-24T02:30:00Z",
                    "payload": { "heart_rate": 75 }
                }
                """;
        JsonNode envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.of(envelope));

        dispatch(topic, payload);

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
    }

    @Test
    void invalidTopic_isNotPublished() {
        String topic = "bad/topic";
        when(topicRouter.route(topic)).thenReturn(Optional.empty());

        dispatch(topic, "{}");

        verify(disruptorPublisher, never()).publish(any());
    }

    @Test
    void invalidPayload_isNotPublished() {
        String topic = "elder/P001/MATTRESS/DEV-001/up/telemetry";
        when(topicRouter.route(topic)).thenReturn(Optional.of(
                new TopicRouter.RouteResult("P001", "MATTRESS", "DEV-001", "telemetry")));
        when(messageValidator.validate(any(byte[].class))).thenReturn(Optional.empty());

        dispatch(topic, "{}");

        verify(disruptorPublisher, never()).publish(any());
    }

    @Test
    void destroyDisconnects() {
        subscriber.destroy();
        verify(connectionManager).disconnect();
    }

    private void dispatch(String topic, String payload) {
        ArgumentCaptor<MqttConnectionManager.MessageHandler> captor = ArgumentCaptor.forClass(MqttConnectionManager.MessageHandler.class);
        subscriber.init();
        verify(connectionManager).setMessageHandler(captor.capture());
        captor.getValue().handle(topic, payload.getBytes(StandardCharsets.UTF_8));
    }
}
