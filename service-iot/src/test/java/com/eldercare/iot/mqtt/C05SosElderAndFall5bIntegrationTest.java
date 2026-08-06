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
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in joint-test material for C05-01 (SOS with an active ELDER binding and a
 * complete LOCATION snapshot) and C05-02 (FALL through the same production path).
 * Real local EMQX ingress - real IoT pipeline - real shared RocketMQ Broker; no
 * RocketMQ mock is imported. A verification consumer with an exclusive group asserts
 * the Broker delivered the generated eventId on the expected topic/tag.
 * <p>
 * Downstream alert creation itself (only one alert, correct fields) is confirmed by
 * alert-service (Sun Jie) from the logged eventId/traceId/sourceMessageId.
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=true",
        "mqtt.broker-url=tcp://localhost:1883",
        "mqtt.client-id=iot-service-mqtt-sos-fall-5b",
        "mqtt.topics[0]=elder/+/+/+/up/+",
        "mqtt.qos[0]=1",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.sos-fall.enabled", matches = "true")
class C05SosElderAndFall5bIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String MQTT_BROKER = "tcp://localhost:1883";
    private static final String C05_PARK_ID = "1988123456789012301";
    private static final long C05_ELDER_ID = 1001L;

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
    private ObjectMapper objectMapper;

    private String createdDeviceId;
    private volatile DefaultMQPushConsumer verificationConsumer;
    private final CopyOnWriteArrayList<String> receivedEventIds = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void requireSharedNameServer() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        assertNotNull(nameServer,
                "C05 SOS/FALL 5B verification requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "C05 SOS/FALL 5B verification must target a non-loopback shared RocketMQ NameServer");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (verificationConsumer != null) {
            verificationConsumer.shutdown();
        }
        if (createdDeviceId != null) {
            outboxMapper.delete(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getDeviceId, createdDeviceId));
            bindingMapper.delete(new LambdaQueryWrapper<IotDeviceBinding>()
                    .eq(IotDeviceBinding::getDeviceId, createdDeviceId));
            instanceMapper.delete(new LambdaQueryWrapper<IotDeviceInstance>()
                    .eq(IotDeviceInstance::getDeviceId, createdDeviceId));
        }
    }

    @Test
    void c05_01_sosWithElderBinding_reachesBrokerAndCarriesElderSnapshot() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to local EMQX");

        String deviceId = "5B-C05-01-SOS-" + UUID.randomUUID();
        String sourceMessageId = "5B-C05-01-MSG-" + UUID.randomUUID();
        createdDeviceId = deviceId;
        createActiveDevice(deviceId, "SOS_BUTTON");
        IotDeviceBinding elderBinding = createElderBinding(deviceId, C05_ELDER_ID);
        IotDeviceBinding locationBinding = createLocationBinding(deviceId);

        startVerificationConsumer(MqTopicConstants.SOS_EVENT_TOPIC, MqTopicConstants.TAG_SOS);
        publish("elder/" + C05_PARK_ID + "/SOS_BUTTON/" + deviceId + "/up/event",
                baseEnvelope(sourceMessageId, deviceId, "SOS", Map.of(
                        "triggerType", "BUTTON_PRESS", "batteryLevel", 86)));

        IotMqOutbox outbox = awaitOutbox(deviceId, sourceMessageId);
        assertEquals(MqTopicConstants.SOS_EVENT_TOPIC, outbox.getTopic());
        assertEquals(MqTopicConstants.TAG_SOS, outbox.getTag());
        assertTrue(outbox.getEventId().matches("[1-9]\\d*"));

        JsonNode envelope = objectMapper.readTree(outbox.getRawEnvelopeJson());
        assertEquals("SOS_TRIGGERED", envelope.path("eventType").asText());
        assertEquals(String.valueOf(C05_ELDER_ID), envelope.path("payload").path("elderId").asText(),
                "C05-01 must carry the active ELDER binding id");
        assertEquals(elderBinding.getBindingId(),
                envelope.path("payload").path("bindingId").asText());
        assertEquals(locationBinding.getBindingId(),
                envelope.path("payload").path("locationBindingId").asText());
        assertEquals(C05_PARK_ID, envelope.path("payload").path("parkId").asText());
        assertEquals("BUTTON_PRESS", envelope.path("payload").path("triggerType").asText());
        assertNotNull(envelope.path("payload").path("location").path("locationId").asText());

        awaitDelivered(outbox.getEventId());

        log.info("C05-01 evidence: eventId={}, traceId={}, sourceMessageId={}, deviceId={}, "
                        + "parkId={}, locationBindingId={}, elderId={}, bindingId={}, "
                        + "eventType={}, topic={}, tag={}, outboxStatus={}",
                outbox.getEventId(), envelope.path("traceId").asText(), sourceMessageId, deviceId,
                envelope.path("payload").path("parkId").asText(),
                envelope.path("payload").path("locationBindingId").asText(),
                envelope.path("payload").path("elderId").asText(),
                envelope.path("payload").path("bindingId").asText(),
                envelope.path("eventType").asText(), outbox.getTopic(), outbox.getTag(),
                outbox.getStatus());
    }

    @Test
    void c05_02_fall_reachesBrokerOnFallTag() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to local EMQX");

        String deviceId = "5B-C05-02-FALL-" + UUID.randomUUID();
        String sourceMessageId = "5B-C05-02-MSG-" + UUID.randomUUID();
        createdDeviceId = deviceId;
        createActiveDevice(deviceId, "FALL_SENSOR");
        IotDeviceBinding locationBinding = createLocationBinding(deviceId);

        startVerificationConsumer(MqTopicConstants.SOS_EVENT_TOPIC, MqTopicConstants.TAG_FALL);
        publish("elder/" + C05_PARK_ID + "/FALL_SENSOR/" + deviceId + "/up/event",
                baseEnvelope(sourceMessageId, deviceId, "FALL", Map.of(
                        "triggerType", "FALL_DOWN", "batteryLevel", 80)));

        IotMqOutbox outbox = awaitOutbox(deviceId, sourceMessageId);
        assertEquals(MqTopicConstants.SOS_EVENT_TOPIC, outbox.getTopic());
        assertEquals(MqTopicConstants.TAG_FALL, outbox.getTag());
        assertTrue(outbox.getEventId().matches("[1-9]\\d*"));

        JsonNode envelope = objectMapper.readTree(outbox.getRawEnvelopeJson());
        assertEquals("FALL_DETECTED", envelope.path("eventType").asText());
        assertEquals(locationBinding.getBindingId(),
                envelope.path("payload").path("locationBindingId").asText());
        assertEquals(C05_PARK_ID, envelope.path("payload").path("parkId").asText());
        assertEquals("FALL_DOWN", envelope.path("payload").path("triggerType").asText());
        assertNotNull(envelope.path("payload").path("location").path("locationName").asText());

        awaitDelivered(outbox.getEventId());

        log.info("C05-02 evidence: eventId={}, traceId={}, sourceMessageId={}, deviceId={}, "
                        + "parkId={}, locationBindingId={}, locationId={}, eventType={}, "
                        + "topic={}, tag={}, outboxStatus={}",
                outbox.getEventId(), envelope.path("traceId").asText(), sourceMessageId, deviceId,
                envelope.path("payload").path("parkId").asText(),
                envelope.path("payload").path("locationBindingId").asText(),
                envelope.path("payload").path("location").path("locationId").asText(),
                envelope.path("eventType").asText(), outbox.getTopic(), outbox.getTag(),
                outbox.getStatus());
    }

    private void createActiveDevice(String deviceId, String deviceType) {
        Long modelId = IdWorker.getId();
        IotDeviceModel model = new IotDeviceModel();
        model.setId(modelId);
        model.setModelCode("5B-C05-" + deviceType + "-MODEL-" + UUID.randomUUID());
        model.setManufacturer("eldercare");
        model.setDeviceType(deviceType);
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(15);
        model.setEnabled(true);
        model.setVersion(0);
        model.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, modelMapper.insert(model));

        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setId(IdWorker.getId());
        instance.setDeviceId(deviceId);
        instance.setSerialNo("5B-C05-" + deviceType + "-SN-" + UUID.randomUUID());
        instance.setModelId(modelId);
        instance.setMqttClientId("5B-C05-" + deviceType + "-CLIENT-" + UUID.randomUUID());
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        instance.setVersion(0);
        instance.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, instanceMapper.insert(instance));
    }

    private IotDeviceBinding createElderBinding(String deviceId, Long elderId) {
        IotDeviceBinding binding = baseBinding(deviceId, BindingType.ELDER.getCode());
        binding.setElderId(elderId);
        binding.setParkId(C05_PARK_ID);
        binding.setBuildingId("B-01");
        binding.setRoomId("R-0101");
        binding.setRoomNo("101");
        assertEquals(1, bindingMapper.insert(binding));
        return binding;
    }

    private IotDeviceBinding createLocationBinding(String deviceId) {
        IotDeviceBinding binding = baseBinding(deviceId, BindingType.LOCATION.getCode());
        binding.setLocationId("5B-C05-LOC-" + UUID.randomUUID());
        binding.setLocationType("PUBLIC_AREA");
        binding.setLocationName("C05 SOS/FALL Joint Test Location");
        binding.setFloorId("F01");
        binding.setParkId(C05_PARK_ID);
        assertEquals(1, bindingMapper.insert(binding));
        return binding;
    }

    private IotDeviceBinding baseBinding(String deviceId, String bindingType) {
        IotDeviceBinding binding = new IotDeviceBinding();
        binding.setId(IdWorker.getId());
        binding.setBindingId(String.valueOf(IdWorker.getId()));
        binding.setDeviceId(deviceId);
        binding.setBindingType(bindingType);
        binding.setStatus(BindingStatus.ACTIVE.getCode());
        binding.setActiveFrom(OffsetDateTime.now());
        binding.setCreatedAt(OffsetDateTime.now());
        return binding;
    }

    private void startVerificationConsumer(String topic, String tag) throws Exception {
        receivedEventIds.clear();
        verificationConsumer = new DefaultMQPushConsumer("C05-SOS-FALL-VERIFY-" + UUID.randomUUID());
        verificationConsumer.setNamesrvAddr(System.getProperty(NAME_SERVER_PROPERTY));
        verificationConsumer.subscribe(topic, tag);
        verificationConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        verificationConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                try {
                    JsonNode body = objectMapper.readTree(msg.getBody());
                    String eventId = body.path("eventId").asText();
                    if (!eventId.isBlank()) {
                        receivedEventIds.add(eventId);
                    }
                } catch (Exception ignored) {
                    // non-C05 traffic on the shared topic is not part of this verification
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        verificationConsumer.start();
    }

    private IotMqOutbox awaitOutbox(String deviceId, String sourceMessageId) throws InterruptedException {
        final IotMqOutbox[] persisted = new IotMqOutbox[1];
        await(() -> {
            persisted[0] = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getDeviceId, deviceId)
                    .eq(IotMqOutbox::getSourceMessageId, sourceMessageId));
            return persisted[0] != null && "SENT".equals(persisted[0].getStatus());
        }, 15_000, "C05 Outbox was not marked SENT after real MQTT and RocketMQ delivery");
        return persisted[0];
    }

    private void awaitDelivered(String eventId) throws InterruptedException {
        await(() -> receivedEventIds.contains(eventId), 30_000,
                "the real Broker did not deliver the C05 message to the verification consumer");
    }

    private Map<String, Object> baseEnvelope(String sourceMessageId, String deviceId,
                                             String messageType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", sourceMessageId);
        envelope.put("deviceId", deviceId);
        envelope.put("messageType", messageType);
        envelope.put("protocolVersion", "1.0");
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("payload", payload);
        return envelope;
    }

    private void publish(String topic, Map<String, Object> envelope) throws Exception {
        MqttClient publisher = new MqttClient(
                MQTT_BROKER,
                "5b-c05-sos-fall-publisher-" + UUID.randomUUID(),
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
