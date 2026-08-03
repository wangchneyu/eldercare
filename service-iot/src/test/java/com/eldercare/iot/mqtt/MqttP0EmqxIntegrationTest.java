package com.eldercare.iot.mqtt;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.IntegrationTestConfig;
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
import com.eldercare.iot.mq.OutboxRetryTask;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Opt-in EMQX smoke test for the P0 and heartbeat paths. It requires PostgreSQL and
 * EMQX 5.8.8 on localhost, and deliberately keeps RocketMQ mocked so MQTT acknowledgement,
 * Outbox durability and heartbeat-to-REST behavior can be isolated from MQ broker availability.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mqtt.auto-connect=true",
        "mqtt.broker-url=tcp://localhost:1883",
        "mqtt.client-id=iot-service-e2e",
        "mqtt.topics[0]=elder/+/+/+/up/+",
        "mqtt.qos[0]=1",
        "iot.outbox.initial-lease-seconds=1",
        "iot.outbox.retry.base-backoff-seconds=1",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
@EnabledIfSystemProperty(named = "iot.e2e.enabled", matches = "true")
class MqttP0EmqxIntegrationTest {

    @Autowired
    private MqttConnectionManager connectionManager;
    @Autowired
    private OutboxRetryTask outboxRetryTask;
    @Autowired
    private IotDeviceModelMapper modelMapper;
    @Autowired
    private IotDeviceInstanceMapper instanceMapper;
    @Autowired
    private IotDeviceBindingMapper bindingMapper;
    @Autowired
    private IotMqOutboxMapper outboxMapper;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ObjectMapper objectMapper;
    @LocalServerPort
    private int port;

    private Long modelId;
    private Long instanceId;
    private Long outboxId;
    private String createdDeviceId;
    private final List<Long> additionalOutboxIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long additionalOutboxId : additionalOutboxIds) {
            outboxMapper.deleteById(additionalOutboxId);
        }
        if (outboxId != null) {
            outboxMapper.deleteById(outboxId);
        }
        if (createdDeviceId != null) {
            bindingMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceBinding>()
                    .eq(IotDeviceBinding::getDeviceId, createdDeviceId));
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
        createActiveSosButtonDevice(deviceId);
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

    @Test
    void qos1DuplicateSos_createsOnlyOneOutboxRecord() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to EMQX");
        stubRocketMqSuccess();

        String deviceId = "DEDUP-" + UUID.randomUUID();
        String messageId = "MSG-" + UUID.randomUUID();
        createActiveSosButtonDevice(deviceId);
        double acknowledgementsBefore = ackCount();

        publishQos1(deviceId, messageId);
        IotMqOutbox outbox = awaitOutbox(deviceId, messageId);
        outboxId = outbox.getId();
        await(() -> "SENT".equals(outboxMapper.selectById(outboxId).getStatus()), 10_000,
                "first P0 Outbox was not marked SENT after mocked RocketMQ confirmation");

        publishQos1(deviceId, messageId);
        await(() -> ackCount() == acknowledgementsBefore + 2, 10_000,
                "duplicate QoS1 SOS was not acknowledged after deduplication");

        long outboxCount = outboxMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotMqOutbox>()
                        .eq(IotMqOutbox::getDeviceId, deviceId)
                        .eq(IotMqOutbox::getSourceMessageId, messageId)
                        .eq(IotMqOutbox::getEventType, "SOS_TRIGGERED")
        );
        assertEquals(1, outboxCount, "duplicate SOS must not create a second Outbox record");
    }

    @Test
    void qos1Sos_staysPendingAfterSendFailure_andRecoversThroughLeaseRetry() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to EMQX");

        AtomicInteger sendAttempts = new AtomicInteger();
        List<String> sentBodies = new ArrayList<>();
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong())).thenAnswer(invocation -> {
            Message<?> message = invocation.getArgument(1, Message.class);
            sentBodies.add((String) message.getPayload());
            if (sendAttempts.incrementAndGet() <= 3) {
                throw new RuntimeException("controlled local RocketMQ failure");
            }
            SendResult result = new SendResult();
            result.setSendStatus(SendStatus.SEND_OK);
            return result;
        });

        String deviceId = "RECOVER-" + UUID.randomUUID();
        String messageId = "MSG-" + UUID.randomUUID();
        createActiveSosButtonDevice(deviceId);
        double acknowledgementsBefore = ackCount();

        publishQos1(deviceId, messageId);
        IotMqOutbox outbox = awaitOutbox(deviceId, messageId);
        outboxId = outbox.getId();
        String frozenEnvelope = outbox.getRawEnvelopeJson();

        await(() -> {
            IotMqOutbox current = outboxMapper.selectById(outboxId);
            return current != null && "PENDING".equals(current.getStatus()) && current.getRetryCount() == 3;
        }, 10_000, "recoverable send failure must keep the P0 Outbox PENDING");
        await(() -> ackCount() == acknowledgementsBefore + 1, 10_000,
                "MQTT message must be acknowledged after the Outbox transaction commits");

        Thread.sleep(1_500);
        outboxRetryTask.retryPending();
        await(() -> "SENT".equals(outboxMapper.selectById(outboxId).getStatus()), 10_000,
                "expired lease was not reclaimed and sent by the retry task");

        assertEquals(4, sendAttempts.get(), "three foreground attempts plus one recovery attempt are expected");
        assertEquals(4, sentBodies.size());
        assertTrue(sentBodies.stream().allMatch(frozenEnvelope::equals),
                "foreground and recovery sends must reuse the persisted C05 envelope byte-for-byte");
    }

    @Test
    void qos1UnboundSos_isPersistedWithExplicitNullElderId() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to EMQX");
        stubRocketMqSuccess();

        String deviceId = "UNBOUND-" + UUID.randomUUID();
        String messageId = "MSG-" + UUID.randomUUID();
        createActiveSosButtonDevice(deviceId);

        publishQos1(deviceId, messageId);

        IotMqOutbox outbox = awaitOutbox(deviceId, messageId);
        outboxId = outbox.getId();
        JsonNode envelope = objectMapper.readTree(outbox.getRawEnvelopeJson());
        assertTrue(envelope.path("payload").has("elderId"));
        assertTrue(envelope.path("payload").path("elderId").isNull(),
                "unbound SOS must keep elderId as an explicit null instead of being dropped");
    }

    @Test
    void qos1Sos_preservesOriginalLocationSnapshotAfterRebind() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to EMQX");
        stubRocketMqSuccess();

        String deviceId = "SNAPSHOT-" + UUID.randomUUID();
        createActiveSimulatorDevice(deviceId, "SOS_BUTTON", 15);
        IotDeviceBinding oldBinding = createLocationBinding(deviceId, "LOC-OLD", "旧活动区");

        String beforeRebindMessageId = "MSG-" + UUID.randomUUID();
        publishQos1(deviceId, beforeRebindMessageId);
        IotMqOutbox beforeRebind = awaitOutbox(deviceId, beforeRebindMessageId);
        outboxId = beforeRebind.getId();
        assertLocationSnapshot(beforeRebind, oldBinding.getBindingId(), "LOC-OLD");

        oldBinding.setStatus(BindingStatus.INACTIVE.getCode());
        oldBinding.setInactiveAt(OffsetDateTime.now());
        assertEquals(1, bindingMapper.updateById(oldBinding));
        IotDeviceBinding newBinding = createLocationBinding(deviceId, "LOC-NEW", "新康复区");

        String afterRebindMessageId = "MSG-" + UUID.randomUUID();
        publishQos1(deviceId, afterRebindMessageId);
        IotMqOutbox afterRebind = awaitOutbox(deviceId, afterRebindMessageId);
        additionalOutboxIds.add(afterRebind.getId());
        assertLocationSnapshot(afterRebind, newBinding.getBindingId(), "LOC-NEW");

        assertLocationSnapshot(outboxMapper.selectById(beforeRebind.getId()), oldBinding.getBindingId(), "LOC-OLD");
    }

    @Test
    void qos1Heartbeat_updatesStatusEndpoint_timesOut_andRecovers() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to EMQX");

        String deviceId = "HB-E2E-" + UUID.randomUUID();
        createActiveSimulatorDevice(deviceId, "RADAR", 5);

        publishHeartbeat(deviceId, "HB-MSG-1-" + UUID.randomUUID());
        await(() -> "ONLINE".equals(readOnlineStatus(deviceId)), 10_000,
                "heartbeat did not update the REST status endpoint to ONLINE");

        await(() -> "OFFLINE".equals(readOnlineStatus(deviceId)), 10_000,
                "heartbeat timeout did not update the REST status endpoint to OFFLINE");
        assertStatusEvents(deviceId, "ONLINE", "OFFLINE");

        publishHeartbeat(deviceId, "HB-MSG-2-" + UUID.randomUUID());
        await(() -> "ONLINE".equals(readOnlineStatus(deviceId)), 10_000,
                "recovered heartbeat did not update the REST status endpoint to ONLINE");
        assertStatusEvents(deviceId, "ONLINE", "OFFLINE", "RECOVERED");
    }

    private void createActiveSimulatorDevice(String deviceId, String deviceType, int heartbeatTimeoutSeconds) {
        createdDeviceId = deviceId;
        modelId = IdWorker.getId();
        IotDeviceModel model = new IotDeviceModel();
        model.setId(modelId);
        model.setModelCode("E2E-MODEL-" + UUID.randomUUID());
        model.setManufacturer("eldercare");
        model.setDeviceType(deviceType);
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(heartbeatTimeoutSeconds);
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

    private void createActiveSosButtonDevice(String deviceId) {
        createActiveSimulatorDevice(deviceId, "SOS_BUTTON", 15);
        createLocationBinding(deviceId, "LOC-DEFAULT-" + UUID.randomUUID(), "Default Test Location");
    }

    private IotDeviceBinding createLocationBinding(String deviceId, String locationId, String locationName) {
        IotDeviceBinding binding = new IotDeviceBinding();
        binding.setId(IdWorker.getId());
        binding.setBindingId("BIND-" + UUID.randomUUID());
        binding.setDeviceId(deviceId);
        binding.setBindingType(BindingType.LOCATION.getCode());
        binding.setLocationId(locationId);
        binding.setLocationType("PUBLIC_AREA");
        binding.setLocationName(locationName);
        binding.setFloorId("F03");
        binding.setStatus(BindingStatus.ACTIVE.getCode());
        binding.setActiveFrom(OffsetDateTime.now());
        binding.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, bindingMapper.insert(binding));
        return binding;
    }

    private void assertLocationSnapshot(IotMqOutbox outbox, String expectedBindingId, String expectedLocationId)
            throws Exception {
        JsonNode payload = objectMapper.readTree(outbox.getRawEnvelopeJson()).path("payload");
        assertEquals(expectedBindingId, payload.path("locationBindingId").asText());
        assertEquals(expectedLocationId, payload.path("location").path("locationId").asText());
    }

    private void publishQos1(String deviceId, String messageId) throws Exception {
        String topic = "elder/P001/SOS_BUTTON/" + deviceId + "/up/event";
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

    private void publishHeartbeat(String deviceId, String messageId) throws Exception {
        String topic = "elder/P001/RADAR/" + deviceId + "/up/heartbeat";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("deviceId", deviceId);
        payload.put("messageType", "HEARTBEAT");
        payload.put("protocolVersion", "1.0");
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("payload", Map.of());

        MqttClient publisher = new MqttClient("tcp://localhost:1883", "heartbeat-publisher-" + UUID.randomUUID(),
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

    private String readOnlineStatus(String deviceId) {
        try {
            return getJson("/iot/devices/" + deviceId + "/status")
                    .path("data").path("onlineStatus").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private void assertStatusEvents(String deviceId, String... expectedEventTypes) throws Exception {
        JsonNode events = getJson("/iot/devices/" + deviceId + "/status-events").path("data");
        assertEquals(expectedEventTypes.length, events.size());
        for (int i = 0; i < expectedEventTypes.length; i++) {
            assertEquals(expectedEventTypes[i], events.get(i).path("eventType").asText());
        }
    }

    private JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
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
