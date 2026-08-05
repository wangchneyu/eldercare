package com.eldercare.iot.mqtt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.BindingStatus;
import com.eldercare.iot.enums.BindingType;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.mq.MqTopicConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in continuous verification from real local EMQX ingress through the IoT
 * pipeline to the shared RocketMQ Broker. No RocketMQ mock is imported.
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=true",
        "mqtt.broker-url=tcp://localhost:1883",
        "mqtt.client-id=iot-service-mqtt-rocketmq-5b",
        "mqtt.topics[0]=elder/+/+/+/up/+",
        "mqtt.qos[0]=1",
        "iot.outbox.retry.fixed-delay-ms=60000",
        "iot.vital-delivery.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.mqtt.rocketmq.5b.enabled", matches = "true")
class MqttRocketMq5bIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String MQTT_BROKER = "tcp://localhost:1883";

    @Autowired
    private MqttConnectionManager connectionManager;
    @Autowired
    private IotDeviceModelMapper modelMapper;
    @Autowired
    private IotDeviceInstanceMapper instanceMapper;
    @Autowired
    private IotDeviceBindingMapper bindingMapper;
    @Autowired
    private IotMqOutboxMapper outboxMapper;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ObjectMapper objectMapper;

    private Long createdModelId;
    private Long createdInstanceId;
    private String createdDeviceId;

    @BeforeAll
    static void requireSharedNameServer() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        assertNotNull(nameServer,
                "continuous 5B verification requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "continuous 5B verification must target a non-loopback shared RocketMQ NameServer");
    }

    @AfterEach
    void cleanUp() {
        if (createdDeviceId != null) {
            outboxMapper.delete(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getDeviceId, createdDeviceId));
            bindingMapper.delete(new LambdaQueryWrapper<IotDeviceBinding>()
                    .eq(IotDeviceBinding::getDeviceId, createdDeviceId));
        }
        if (createdInstanceId != null) {
            instanceMapper.deleteById(createdInstanceId);
        }
        if (createdModelId != null) {
            modelMapper.deleteById(createdModelId);
        }
    }

    @Test
    void c04_realMqttVitalSign_reachesSharedRocketMqBroker() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to local EMQX");

        String deviceId = "5B-MQTT-C04-" + UUID.randomUUID();
        String sourceMessageId = "5B-MQTT-C04-MSG-" + UUID.randomUUID();
        createActiveDevice(deviceId, "MATTRESS");
        createElderBinding(deviceId, 2001L);

        double sendSuccessBefore = mqSuccessCount(MqTopicConstants.VITAL_SIGN_TOPIC, "MATTRESS");
        double acknowledgementsBefore = ackCount();
        publishVitalSign(deviceId, sourceMessageId);

        await(() -> mqSuccessCount(MqTopicConstants.VITAL_SIGN_TOPIC, "MATTRESS") > sendSuccessBefore,
                15_000, "C04 did not receive SEND_OK after real MQTT ingress");
        await(() -> ackCount() > acknowledgementsBefore,
                10_000, "C04 MQTT QoS1 message was not acknowledged after pipeline acceptance");

        log.info("Continuous 5B C04 confirmed: sourceMessageId={}, deviceId={}, topic={}, tag={}, nameServer={}",
                sourceMessageId, deviceId, MqTopicConstants.VITAL_SIGN_TOPIC, "MATTRESS",
                System.getProperty(NAME_SERVER_PROPERTY));
    }

    @Test
    void c05_realMqttSos_reachesSharedRocketMqBrokerAndMarksOutboxSent() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to local EMQX");

        String deviceId = "5B-MQTT-C05-" + UUID.randomUUID();
        String sourceMessageId = "5B-MQTT-C05-MSG-" + UUID.randomUUID();
        createActiveDevice(deviceId, "SOS_BUTTON");
        IotDeviceBinding locationBinding = createLocationBinding(deviceId);

        double acknowledgementsBefore = ackCount();
        publishSos(deviceId, sourceMessageId);

        final IotMqOutbox[] persisted = new IotMqOutbox[1];
        await(() -> {
            persisted[0] = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getDeviceId, deviceId)
                    .eq(IotMqOutbox::getSourceMessageId, sourceMessageId));
            return persisted[0] != null && "SENT".equals(persisted[0].getStatus());
        }, 15_000, "C05 Outbox was not marked SENT after real MQTT and RocketMQ delivery");
        await(() -> ackCount() > acknowledgementsBefore,
                10_000, "C05 MQTT QoS1 message was not acknowledged after Outbox commit");

        IotMqOutbox outbox = persisted[0];
        assertEquals(MqTopicConstants.SOS_EVENT_TOPIC, outbox.getTopic());
        assertEquals(MqTopicConstants.TAG_SOS, outbox.getTag());
        assertNotNull(outbox.getSentAt());
        assertTrue(outbox.getEventId().matches("\\d+"));

        JsonNode envelope = objectMapper.readTree(outbox.getRawEnvelopeJson());
        assertEquals(outbox.getEventId(), envelope.path("eventId").asText());
        assertEquals(sourceMessageId, envelope.path("payload").path("sourceMessageId").asText());
        assertEquals(locationBinding.getBindingId(),
                envelope.path("payload").path("locationBindingId").asText());
        assertEquals("SOS_TRIGGERED", envelope.path("eventType").asText());

        log.info("Continuous 5B C05 confirmed: sourceMessageId={}, eventId={}, deviceId={}, topic={}, tag={}, status={}",
                sourceMessageId, outbox.getEventId(), deviceId, outbox.getTopic(), outbox.getTag(), outbox.getStatus());
    }

    private void createActiveDevice(String deviceId, String deviceType) {
        createdDeviceId = deviceId;
        createdModelId = IdWorker.getId();
        IotDeviceModel model = new IotDeviceModel();
        model.setId(createdModelId);
        model.setModelCode("5B-MQTT-MODEL-" + UUID.randomUUID());
        model.setManufacturer("eldercare");
        model.setDeviceType(deviceType);
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(15);
        model.setEnabled(true);
        model.setVersion(0);
        model.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, modelMapper.insert(model));

        createdInstanceId = IdWorker.getId();
        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setId(createdInstanceId);
        instance.setDeviceId(deviceId);
        instance.setSerialNo("5B-MQTT-SN-" + UUID.randomUUID());
        instance.setModelId(createdModelId);
        instance.setMqttClientId("5B-MQTT-CLIENT-" + UUID.randomUUID());
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        instance.setVersion(0);
        instance.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, instanceMapper.insert(instance));
    }

    private IotDeviceBinding createElderBinding(String deviceId, Long elderId) {
        IotDeviceBinding binding = baseBinding(deviceId, BindingType.ELDER.getCode());
        binding.setElderId(elderId);
        binding.setParkId("P001");
        binding.setBuildingId("B01");
        binding.setRoomId("R0101");
        binding.setRoomNo("101");
        assertEquals(1, bindingMapper.insert(binding));
        return binding;
    }

    private IotDeviceBinding createLocationBinding(String deviceId) {
        IotDeviceBinding binding = baseBinding(deviceId, BindingType.LOCATION.getCode());
        binding.setLocationId("5B-LOCATION-" + UUID.randomUUID());
        binding.setLocationType("PUBLIC_AREA");
        binding.setLocationName("RocketMQ Continuous Test Location");
        binding.setFloorId("F01");
        assertEquals(1, bindingMapper.insert(binding));
        return binding;
    }

    private IotDeviceBinding baseBinding(String deviceId, String bindingType) {
        IotDeviceBinding binding = new IotDeviceBinding();
        binding.setId(IdWorker.getId());
        binding.setBindingId("5B-BIND-" + UUID.randomUUID());
        binding.setDeviceId(deviceId);
        binding.setBindingType(bindingType);
        binding.setStatus(BindingStatus.ACTIVE.getCode());
        binding.setActiveFrom(OffsetDateTime.now());
        binding.setCreatedAt(OffsetDateTime.now());
        return binding;
    }

    private void publishVitalSign(String deviceId, String sourceMessageId) throws Exception {
        Map<String, Object> envelope = baseEnvelope(sourceMessageId, deviceId, "VITAL_SIGN");
        envelope.put("payload", Map.of(
                "heart_rate", 72,
                "respiratory_rate", 18,
                "body_movement", 3,
                "bed_status", "IN_BED"
        ));
        publish("elder/P001/MATTRESS/" + deviceId + "/up/telemetry", envelope);
    }

    private void publishSos(String deviceId, String sourceMessageId) throws Exception {
        Map<String, Object> envelope = baseEnvelope(sourceMessageId, deviceId, "SOS");
        envelope.put("payload", Map.of("triggerType", "BUTTON_PRESS", "batteryLevel", 85));
        publish("elder/P001/SOS_BUTTON/" + deviceId + "/up/event", envelope);
    }

    private Map<String, Object> baseEnvelope(String sourceMessageId, String deviceId, String messageType) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", sourceMessageId);
        envelope.put("deviceId", deviceId);
        envelope.put("messageType", messageType);
        envelope.put("protocolVersion", "1.0");
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        return envelope;
    }

    private void publish(String topic, Map<String, Object> envelope) throws Exception {
        MqttClient publisher = new MqttClient(
                MQTT_BROKER,
                "5b-continuous-publisher-" + UUID.randomUUID(),
                new MemoryPersistence()
        );
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            publisher.connect(options);
            MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(envelope));
            message.setQos(1);
            publisher.publish(topic, message);
        } finally {
            if (publisher.isConnected()) {
                publisher.disconnect();
            }
            publisher.close();
        }
    }

    private double mqSuccessCount(String topic, String tag) {
        Counter counter = meterRegistry.find("iot_mq_send_total")
                .tags("topic", topic, "tag", tag, "status", "success")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double ackCount() {
        Counter counter = meterRegistry.find("iot_mqtt_acks_total")
                .tags("status", "acked", "qos", "1")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, String failureMessage)
            throws InterruptedException {
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
