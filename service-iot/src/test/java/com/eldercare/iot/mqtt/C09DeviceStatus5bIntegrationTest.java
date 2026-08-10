package com.eldercare.iot.mqtt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
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

import java.nio.charset.StandardCharsets;
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
 * Opt-in 5B joint-test material for C09 {@code elder-device-status}: a real local
 * EMQX heartbeat ingress drives the production HeartbeatManager state machine through
 * ONLINE (UNKNOWN->ONLINE), OFFLINE (timeout) and RECOVERED (OFFLINE->ONLINE)
 * transitions, and a standalone verification consumer with an exclusive group asserts
 * the shared RocketMQ Broker delivered the frozen six-field body plus the
 * {@code X-Trace-Id} header. C09 has no frozen tag; the consumer subscribes with the
 * wildcard tag and filters by the per-run unique deviceId.
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=true",
        "mqtt.broker-url=tcp://localhost:1883",
        "mqtt.client-id=iot-service-mqtt-c09-5b",
        "mqtt.topics[0]=elder/+/+/+/up/+",
        "mqtt.qos[0]=1",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.device-status.enabled", matches = "true")
class C09DeviceStatus5bIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String MQTT_BROKER = "tcp://localhost:1883";
    private static final String C09_PARK_ID = "1988123456789012301";
    private static final String C09_DEVICE_TYPE = "MATTRESS";
    private static final int HEARTBEAT_TIMEOUT_SECONDS = 15;

    @Autowired
    private MqttConnectionManager connectionManager;
    @Autowired
    private IotDeviceModelMapper modelMapper;
    @Autowired
    private IotDeviceInstanceMapper instanceMapper;
    @Autowired
    private IotDeviceBindingMapper bindingMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private String createdDeviceId;
    private volatile DefaultMQPushConsumer verificationConsumer;
    private final CopyOnWriteArrayList<ReceivedStatusMessage> receivedMessages = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void requireSharedNameServer() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        assertNotNull(nameServer,
                "C09 device-status 5B verification requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "C09 device-status 5B verification must target a non-loopback shared RocketMQ NameServer");
    }

    @AfterEach
    void cleanUp() {
        if (verificationConsumer != null) {
            verificationConsumer.shutdown();
        }
        if (createdDeviceId != null) {
            bindingMapper.delete(new LambdaQueryWrapper<IotDeviceBinding>()
                    .eq(IotDeviceBinding::getDeviceId, createdDeviceId));
            instanceMapper.delete(new LambdaQueryWrapper<IotDeviceInstance>()
                    .eq(IotDeviceInstance::getDeviceId, createdDeviceId));
        }
    }

    @Test
    void c09_online_offline_recovered_reachesSharedBrokerWithSixFieldsAndTraceId() throws Exception {
        await(connectionManager::isConnected, 10_000, "service-iot did not connect to local EMQX");

        String deviceId = "5B-C09-MATTRESS-" + UUID.randomUUID();
        createdDeviceId = deviceId;
        createActiveDevice(deviceId);

        startVerificationConsumer();
        Thread.sleep(1_500L);

        long onlineSendAt = System.currentTimeMillis();
        publishHeartbeat("5B-C09-MSG-ONLINE-" + UUID.randomUUID(), deviceId);
        ReceivedStatusMessage online = awaitTransition(deviceId, "UNKNOWN", "ONLINE",
                "C09 ONLINE transition was not delivered to the shared Broker");
        assertTransition(online, "UNKNOWN", "ONLINE", onlineSendAt);
        log.info("C09 ONLINE evidence: sendAtMs={}, deviceId={}, deviceType={}, oldStatus={}, newStatus={}, "
                        + "occurredAt={}, traceId={}, headerXTraceId={}, bodyJson={}, consumerGroup={}",
                onlineSendAt, online.deviceId, online.deviceType, online.oldStatus, online.newStatus,
                online.occurredAt, online.traceId, online.headerTraceId, online.rawJson, online.consumerGroup);

        ReceivedStatusMessage offline = awaitTransition(deviceId, "ONLINE", "OFFLINE",
                "C09 OFFLINE timeout transition was not delivered to the shared Broker");
        assertTransition(offline, "ONLINE", "OFFLINE", Long.MIN_VALUE);
        log.info("C09 OFFLINE evidence: sendAtMs=timeout-scanner, deviceId={}, deviceType={}, oldStatus={}, "
                        + "newStatus={}, occurredAt={}, traceId={}, headerXTraceId={}, bodyJson={}, consumerGroup={}",
                offline.deviceId, offline.deviceType, offline.oldStatus, offline.newStatus,
                offline.occurredAt, offline.traceId, offline.headerTraceId, offline.rawJson, offline.consumerGroup);

        long recoveredSendAt = System.currentTimeMillis();
        publishHeartbeat("5B-C09-MSG-RECOVERED-" + UUID.randomUUID(), deviceId);
        ReceivedStatusMessage recovered = awaitTransition(deviceId, "OFFLINE", "ONLINE",
                "C09 RECOVERED transition was not delivered to the shared Broker");
        assertTransition(recovered, "OFFLINE", "ONLINE", recoveredSendAt);
        log.info("C09 RECOVERED evidence: sendAtMs={}, deviceId={}, deviceType={}, oldStatus={}, newStatus={}, "
                        + "occurredAt={}, traceId={}, headerXTraceId={}, bodyJson={}, consumerGroup={}",
                recoveredSendAt, recovered.deviceId, recovered.deviceType, recovered.oldStatus,
                recovered.newStatus, recovered.occurredAt, recovered.traceId, recovered.headerTraceId,
                recovered.rawJson, recovered.consumerGroup);
    }

    private void createActiveDevice(String deviceId) {
        Long modelId = IdWorker.getId();
        IotDeviceModel model = new IotDeviceModel();
        model.setId(modelId);
        model.setModelCode("5B-C09-MODEL-" + UUID.randomUUID());
        model.setManufacturer("eldercare");
        model.setDeviceType(C09_DEVICE_TYPE);
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(HEARTBEAT_TIMEOUT_SECONDS);
        model.setEnabled(true);
        model.setVersion(0);
        model.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, modelMapper.insert(model));

        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setId(IdWorker.getId());
        instance.setDeviceId(deviceId);
        instance.setSerialNo("5B-C09-SN-" + UUID.randomUUID());
        instance.setModelId(modelId);
        instance.setMqttClientId("5B-C09-CLIENT-" + UUID.randomUUID());
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        instance.setVersion(0);
        instance.setCreatedAt(OffsetDateTime.now());
        assertEquals(1, instanceMapper.insert(instance));
    }

    private void startVerificationConsumer() throws Exception {
        receivedMessages.clear();
        String consumerGroup = "C09-DEVICE-STATUS-VERIFY-" + UUID.randomUUID();
        verificationConsumer = new DefaultMQPushConsumer(consumerGroup);
        verificationConsumer.setNamesrvAddr(System.getProperty(NAME_SERVER_PROPERTY));
        verificationConsumer.subscribe(MqTopicConstants.DEVICE_STATUS_TOPIC, "*");
        verificationConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        verificationConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                try {
                    JsonNode node = objectMapper.readTree(body);
                    String deviceId = node.path("deviceId").asText();
                    if (!deviceId.isBlank() && deviceId.equals(createdDeviceId)) {
                        String headerTraceId = msg.getProperty("X-Trace-Id");
                        receivedMessages.add(new ReceivedStatusMessage(
                                consumerGroup, deviceId, node.path("deviceType").asText(),
                                node.path("oldStatus").asText(), node.path("newStatus").asText(),
                                node.path("occurredAt").asText(), node.path("traceId").asText(),
                                headerTraceId, body,
                                System.currentTimeMillis()));
                    }
                } catch (Exception ignored) {
                    // non-C09 traffic on the shared topic is not part of this verification
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        verificationConsumer.start();
    }

    private ReceivedStatusMessage awaitTransition(String deviceId, String oldStatus, String newStatus,
                                                  String failureMessage) throws InterruptedException {
        final ReceivedStatusMessage[] found = new ReceivedStatusMessage[1];
        await(() -> {
            for (ReceivedStatusMessage message : receivedMessages) {
                if (message.deviceId.equals(deviceId)
                        && message.oldStatus.equals(oldStatus)
                        && message.newStatus.equals(newStatus)) {
                    found[0] = message;
                    return true;
                }
            }
            return false;
        }, HEARTBEAT_TIMEOUT_SECONDS * 1_000L + 30_000L, failureMessage);
        return found[0];
    }

    private void assertTransition(ReceivedStatusMessage message, String oldStatus, String newStatus,
                                  long sendAtMs) {
        assertEquals(oldStatus, message.oldStatus);
        assertEquals(newStatus, message.newStatus);
        assertEquals(C09_DEVICE_TYPE, message.deviceType);
        assertNotNull(message.occurredAt, "C09 six-field body must contain occurredAt");
        assertNotNull(message.traceId, "C09 six-field body must contain traceId");
        assertEquals(message.traceId, message.headerTraceId,
                "C09 X-Trace-Id header must match the traceId field");
        assertTrue(message.headerTraceId != null && !message.headerTraceId.isBlank(),
                "C09 X-Trace-Id header must be present");
        if (sendAtMs != Long.MIN_VALUE) {
            long occurredAtMs = OffsetDateTime.parse(message.occurredAt).toInstant().toEpochMilli();
            assertTrue(occurredAtMs - sendAtMs >= 0 && occurredAtMs - sendAtMs <= 30_000L,
                    "C09 occurredAt must be near the send time");
        }
    }

    private void publishHeartbeat(String sourceMessageId, String deviceId) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", sourceMessageId);
        envelope.put("deviceId", deviceId);
        envelope.put("messageType", "HEARTBEAT");
        envelope.put("protocolVersion", "1.0");
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("payload", Map.of());

        String topic = "elder/" + C09_PARK_ID + "/" + C09_DEVICE_TYPE + "/" + deviceId + "/up/heartbeat";
        MqttClient publisher = new MqttClient(
                MQTT_BROKER,
                "5b-c09-publisher-" + UUID.randomUUID(),
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

    private record ReceivedStatusMessage(
            String consumerGroup,
            String deviceId,
            String deviceType,
            String oldStatus,
            String newStatus,
            String occurredAt,
            String traceId,
            String headerTraceId,
            String rawJson,
            long receivedAtMs) {
    }
}