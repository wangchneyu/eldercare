package com.eldercare.iot.mqtt;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Opt-in EMQX smoke test for the P0 path. It requires PostgreSQL and EMQX 5.8.8
 * on localhost, and deliberately keeps RocketMQ mocked so acknowledgement and
 * Outbox durability can be isolated from MQ broker availability.
 */
@SpringBootTest(properties = {
        "mqtt.auto-connect=true",
        "mqtt.broker-url=tcp://localhost:1883",
        "mqtt.client-id=iot-service-e2e",
        "mqtt.topics[0]=elder/+/+/+/up/+",
        "mqtt.qos[0]=1"
})
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
@EnabledIfSystemProperty(named = "iot.e2e.enabled", matches = "true")
class MqttP0EmqxIntegrationTest {

    @Autowired
    private MqttConnectionManager connectionManager;
    @Autowired
    private IotDeviceModelMapper modelMapper;
    @Autowired
    private IotDeviceInstanceMapper instanceMapper;
    @Autowired
    private IotMqOutboxMapper outboxMapper;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ObjectMapper objectMapper;

    private Long modelId;
    private Long instanceId;
    private Long outboxId;

    @AfterEach
    void cleanUp() {
        if (outboxId != null) {
            outboxMapper.deleteById(outboxId);
        }
        if (instanceId != null) {
            instanceMapper.deleteById(instanceId);
        }
        if (modelId != null) {
            modelMapper.deleteById(modelId);
        }
    }

    @Test
    void qos1Sos_isAckedAfterOutboxCommit_andNotRedeliveredAfterReconnect() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to EMQX");
        stubRocketMqSuccess();

        String deviceId = "E2E-" + UUID.randomUUID();
        String messageId = "MSG-" + UUID.randomUUID();
        createActiveSimulatorDevice(deviceId);
        double acknowledgementsBefore = ackCount();

        publishQos1(deviceId, messageId);

        IotMqOutbox outbox = awaitOutbox(deviceId, messageId);
        outboxId = outbox.getId();
        assertNotNull(outbox.getRawEnvelopeJson());
        await(() -> {
            IotMqOutbox current = outboxMapper.selectById(outboxId);
            return current != null && "SENT".equals(current.getStatus());
        }, 10_000, "P0 Outbox was not marked SENT after mocked RocketMQ confirmation");
        await(() -> ackCount() == acknowledgementsBefore + 1, 10_000, "MQTT acknowledgement was not recorded");

        connectionManager.disconnect();
        await(() -> !connectionManager.isConnected(), 5_000, "service-iot did not disconnect from EMQX");
        connectionManager.connect();
        await(connectionManager::isConnected, 10_000, "service-iot did not reconnect to EMQX");
        Thread.sleep(1_000);

        assertEquals(acknowledgementsBefore + 1, ackCount(),
                "an acknowledged QoS1 message must not be redelivered after persistent-session reconnect");
    }

    private void createActiveSimulatorDevice(String deviceId) {
        modelId = IdWorker.getId();
        IotDeviceModel model = new IotDeviceModel();
        model.setId(modelId);
        model.setModelCode("E2E-MODEL-" + UUID.randomUUID());
        model.setManufacturer("eldercare");
        model.setDeviceType("SOS_BUTTON");
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(15);
        model.setEnabled(true);
        model.setVersion(0);
        model.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, modelMapper.insert(model));

        instanceId = IdWorker.getId();
        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setId(instanceId);
        instance.setDeviceId(deviceId);
        instance.setSerialNo("E2E-SN-" + UUID.randomUUID());
        instance.setModelId(modelId);
        instance.setMqttClientId("E2E-MQTT-" + UUID.randomUUID());
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        instance.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, instanceMapper.insert(instance));
    }

    private void publishQos1(String deviceId, String messageId) throws Exception {
        String topic = "elder/P001/SOS_BUTTON/" + deviceId + "/up/alert";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("deviceId", deviceId);
        payload.put("messageType", "SOS");
        payload.put("protocolVersion", "1.0");
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("payload", Map.of("triggerType", "BUTTON_PRESS", "batteryLevel", 85));

        MqttClient publisher = new MqttClient("tcp://localhost:1883", "e2e-publisher-" + UUID.randomUUID(),
                new MemoryPersistence());
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            publisher.connect(options);
            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            message.setQos(1);
            publisher.publish(topic, message);
        } finally {
            if (publisher.isConnected()) {
                publisher.disconnect();
            }
            publisher.close();
        }
    }

    private IotMqOutbox awaitOutbox(String deviceId, String messageId) throws InterruptedException {
        final IotMqOutbox[] result = new IotMqOutbox[1];
        await(() -> {
            result[0] = outboxMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getDeviceId, deviceId)
                    .eq(IotMqOutbox::getSourceMessageId, messageId));
            return result[0] != null;
        }, 10_000, "Outbox record was not committed");
        return result[0];
    }

    private void stubRocketMqSuccess() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(result);
    }

    private double ackCount() {
        Counter counter = meterRegistry.find("iot_mqtt_acks_total")
                .tags("status", "acked", "qos", "1")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private void await(BooleanSupplier condition, long timeoutMillis, String failureMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail(failureMessage);
    }
}
