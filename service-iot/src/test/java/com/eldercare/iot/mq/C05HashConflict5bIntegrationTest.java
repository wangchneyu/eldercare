package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in joint-test material for C05-05: two real Broker messages carrying the
 * SAME eventId but DIFFERENT payloadHash. alert-service must stop automatic
 * business handling, keep both hashes for manual review, and never overwrite
 * the original event.
 * <p>
 * IoT never produces such a pair through its production paths (eventId is
 * generated once and Outbox replays the frozen envelope byte-for-byte), so this
 * material is constructed directly against the shared RocketMQ Broker with a
 * DefaultMQProducer. A verification consumer asserts both deliveries arrived
 * with the same eventId and byte-different payloads; payloadHash uses SHA-256.
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=false",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.hash-conflict.enabled", matches = "true")
class C05HashConflict5bIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String C05_PARK_ID = "1988123456789012301";
    private static final String C05_LOCATION_BINDING_ID = "1988123456789012303";

    @Autowired
    private ObjectMapper objectMapper;

    private volatile DefaultMQPushConsumer verificationConsumer;

    @BeforeAll
    static void requireSharedNameServer() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        assertNotNull(nameServer,
                "C05 hash-conflict verification requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "C05 hash-conflict verification must target a non-loopback shared RocketMQ NameServer");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (verificationConsumer != null) {
            verificationConsumer.shutdown();
        }
    }

    @Test
    void c05_05_sameEventIdDifferentPayloadHash_reachesBrokerForManualReview() throws Exception {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        String eventId = String.valueOf(IdWorker.getId());
        String traceId = "5b-c05-hash-" + UUID.randomUUID();
        String deviceId = "5B-C05-HASH-DEVICE-" + UUID.randomUUID();

        CopyOnWriteArrayList<String> receivedBodies = new CopyOnWriteArrayList<>();
        AtomicInteger matchingCount = new AtomicInteger();
        verificationConsumer = new DefaultMQPushConsumer("C05-HASH-CONFLICT-VERIFY-" + UUID.randomUUID());
        verificationConsumer.setNamesrvAddr(nameServer);
        verificationConsumer.subscribe(MqTopicConstants.SOS_EVENT_TOPIC, MqTopicConstants.TAG_SOS);
        verificationConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        verificationConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                if (body.contains("\"eventId\":\"" + eventId + "\"")) {
                    receivedBodies.add(body);
                    matchingCount.incrementAndGet();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        verificationConsumer.start();

        // 第一条：eventId=X, payload A（batteryLevel=86）
        String locBinding1 = String.valueOf(IdWorker.getId());
        String locBinding2 = String.valueOf(IdWorker.getId());
        String first = envelope(eventId, traceId, deviceId, "MSG-1-" + UUID.randomUUID(),
                "1e8793b9-41ac-4272-b09a-4c77e6f66422", locBinding1, 86);
        // 第二条：同一 eventId，payload 不同（batteryLevel=85、locationName、locationBindingId 不同）→ payloadHash 必然不同
        String second = envelope(eventId, traceId, deviceId, "MSG-2-" + UUID.randomUUID(),
                "2d64f8a0-52bb-4837-91d5-3a88e6f66423", locBinding2, 85);

        String hash1 = sha256(first);
        String hash2 = sha256(second);
        assertNotEquals(hash1, hash2, "the two payloads must differ (payloadHash must differ)");

        DefaultMQProducer producer = new DefaultMQProducer("C05-HASH-CONFLICT-PRODUCER-" + UUID.randomUUID());
        producer.setNamesrvAddr(nameServer);
        producer.start();
        try {
            producer.send(new Message(MqTopicConstants.SOS_EVENT_TOPIC, MqTopicConstants.TAG_SOS,
                    first.getBytes(StandardCharsets.UTF_8)));
            producer.send(new Message(MqTopicConstants.SOS_EVENT_TOPIC, MqTopicConstants.TAG_SOS,
                    second.getBytes(StandardCharsets.UTF_8)));
        } finally {
            producer.shutdown();
        }

        await(() -> matchingCount.get() >= 2, 30_000,
                "the real Broker did not deliver both same-eventId messages to the verification consumer");
        assertEquals(2, receivedBodies.size());
        String receivedFirst = receivedBodies.get(0);
        String receivedSecond = receivedBodies.get(1);
        assertEquals(eventId, objectMapper.readTree(receivedFirst).path("eventId").asText());
        assertEquals(eventId, objectMapper.readTree(receivedSecond).path("eventId").asText());
        assertNotEquals(receivedFirst, receivedSecond, "payloads must differ");

        log.info("C05-05 evidence: eventId={}, traceId={}, topic={}, tag={}, nameServer={}, "
                        + "deliveriesObserved=2, sameEventId=true, payloadBytesFirst={}, payloadBytesSecond={}, "
                        + "payloadHashFirst={}, payloadHashSecond={}, hashesDiffer=true, "
                        + "downstreamExpected=STOP-AUTOMATIC-HANDLING-AND-MANUAL-REVIEW",
                eventId, traceId, MqTopicConstants.SOS_EVENT_TOPIC, MqTopicConstants.TAG_SOS, nameServer,
                receivedFirst.length(), receivedSecond.length(), hash1, hash2);
    }

    private String envelope(String eventId, String traceId, String deviceId, String sourceMessageId,
                            String locationId, String locationBindingId, int batteryLevel) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "SOS_TRIGGERED");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("traceId", traceId);
        envelope.put("producer", MqTopicConstants.PRODUCER);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceMessageId", sourceMessageId);
        payload.put("deviceId", deviceId);
        payload.put("deviceType", "SOS_BUTTON");
        payload.put("elderId", "1003");
        payload.put("bindingId", "1988123456789012304");
        payload.put("parkId", C05_PARK_ID);
        payload.put("buildingId", null);
        payload.put("roomId", null);
        payload.put("roomNo", null);
        payload.put("locationBindingId", locationBindingId);
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("locationId", locationId);
        location.put("locationType", "PUBLIC_AREA");
        location.put("locationName", "C05 Hash Conflict " + batteryLevel);
        location.put("floorId", "F01");
        payload.put("location", location);
        payload.put("triggerType", "BUTTON_PRESS");
        payload.put("batteryLevel", batteryLevel);
        envelope.put("payload", payload);
        return objectMapper.writeValueAsString(envelope);
    }

    private static String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
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
