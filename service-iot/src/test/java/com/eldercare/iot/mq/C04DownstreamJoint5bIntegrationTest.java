package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in joint-test material for C04-01 / C04-02 / C04-03 targeting vital-sign-service.
 * Real C04 production send path (VitalSignProducer to {@code elder-vital-raw:MATTRESS})
 * against the shared RocketMQ NameServer. A verification consumer with an exclusive group
 * confirms delivery and captures the exact envelope bytes for downstream cross-checking.
 * <p>
 * Downstream persistence itself (only one record per unique sourceMessageId, correct units,
 * elder_id null handling) is confirmed by vital-sign-service (Zhou Guangzhao) from the
 * logged eventId / traceId / sourceMessageId / device_id; it is not claimed by this test.
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=false",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.c04.enabled", matches = "true")
class C04DownstreamJoint5bIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String VITAL_TOPIC = MqTopicConstants.VITAL_SIGN_TOPIC;
    private static final String VITAL_TAG = "MATTRESS";

    @Autowired
    private VitalSignProducer vitalSignProducer;
    @Autowired
    private ObjectMapper objectMapper;

    private volatile DefaultMQPushConsumer verificationConsumer;

    @BeforeAll
    static void requireSharedNameServer() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        assertNotNull(nameServer,
                "C04 downstream joint verification requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "C04 downstream joint verification must target a non-loopback shared RocketMQ NameServer");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (verificationConsumer != null) {
            verificationConsumer.shutdown();
        }
    }

    @Test
    void c04_01_uniqueMessageId_mattressVitalSign_reachesBrokerCarryingRequiredFields() throws Exception {
        String eventId = String.valueOf(IdWorker.getId());
        String sourceMessageId = "5B-C04-01-MSG-" + UUID.randomUUID();
        String traceId = "5b-c04-01-" + UUID.randomUUID();
        String deviceId = "5B-C04-01-MATTRESS-" + UUID.randomUUID();
        long elderId = 3001L;
        long dataTime = System.currentTimeMillis();

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        startVerificationConsumer(received, "\"eventId\":\"" + eventId + "\"");

        vitalSignProducer.send(vitalSign(eventId, sourceMessageId, deviceId, traceId, elderId,
                72, 18, 3, "IN_BED", dataTime));

        String body = awaitSingle(eventId, received, "C04-01 mattress vital sign was not delivered");
        JsonNode envelope = objectMapper.readTree(body);
        JsonNode payload = envelope.path("payload");
        assertEquals(eventId, envelope.path("eventId").asText(), "eventId must round-trip");
        assertEquals("VITAL_SIGN_REPORTED", envelope.path("eventType").asText());
        assertEquals(1, envelope.path("schemaVersion").asInt());
        assertEquals(traceId, envelope.path("traceId").asText());
        assertEquals("service-iot@1.0.0", envelope.path("producer").asText());
        assertEquals(sourceMessageId, payload.path("sourceMessageId").asText());
        assertEquals(deviceId, payload.path("device_id").asText());
        assertEquals(String.valueOf(elderId), payload.path("elder_id").asText(),
                "C04-01 elder_id must be a String");
        assertEquals(dataTime, payload.path("data_time").asLong(),
                "C04-01 data_time must be epoch millis");
        assertEquals(72, payload.path("heart_rate").asInt());
        assertEquals(18, payload.path("respiratory_rate").asInt());
        assertEquals(3, payload.path("body_movement").asInt());
        assertEquals("IN_BED", payload.path("bed_status").asText());

        log.info("C04-01 evidence: eventId={}, traceId={}, sourceMessageId={}, device_id={}, elder_id={}, "
                        + "topic={}, tag={}, receivedBytes={}",
                eventId, traceId, sourceMessageId, deviceId, elderId,
                VITAL_TOPIC, VITAL_TAG, received.get(0).length());
    }

    @Test
    void c04_02_identicalEnvelopeReplayed_sameDeviceAndSourceReachesBrokerTwice() throws Exception {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        String eventId = String.valueOf(IdWorker.getId());
        String sourceMessageId = "5B-C04-02-MSG-" + UUID.randomUUID();
        String traceId = "5b-c04-02-" + UUID.randomUUID();
        String deviceId = "5B-C04-02-MATTRESS-" + UUID.randomUUID();
        long elderId = 3002L;
        long dataTime = System.currentTimeMillis();

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        AtomicInteger matchingCount = new AtomicInteger();
        verificationConsumer = new DefaultMQPushConsumer("C04-JOINT-VERIFY-" + UUID.randomUUID());
        verificationConsumer.setNamesrvAddr(nameServer);
        verificationConsumer.subscribe(VITAL_TOPIC, VITAL_TAG);
        verificationConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        verificationConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                if (body.contains("\"sourceMessageId\":\"" + sourceMessageId + "\"")) {
                    received.add(body);
                    matchingCount.incrementAndGet();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        verificationConsumer.start();

        ParsedVitalSign sample = vitalSign(eventId, sourceMessageId, deviceId, traceId, elderId,
                74, 17, 12, "IN_BED", dataTime);
        vitalSignProducer.send(sample);
        vitalSignProducer.send(sample);

        await(() -> matchingCount.get() >= 2, 30_000,
                "the real Broker did not deliver the replayed C04 envelope twice to the verification consumer");
        assertEquals(2, received.size(), "exactly two identical C04 deliveries are expected");
        assertEquals(received.get(0), received.get(1), "replay must keep the envelope byte-identical");
        JsonNode payload = objectMapper.readTree(received.get(0)).path("payload");
        assertEquals(deviceId, payload.path("device_id").asText());
        assertEquals(sourceMessageId, payload.path("sourceMessageId").asText());

        log.info("C04-02 evidence: eventId={}, traceId={}, sourceMessageId={}, deviceId={}, "
                        + "deliveriesObserved={}, bytesIdentical=true, bytes={}, "
                        + "downstreamIdempotentRecordCount=PENDING-VITAL-SIGN-SERVICE-CONFIRM",
                eventId, traceId, sourceMessageId, deviceId, received.size(), received.get(0).length());
    }

    @Test
    void c04_03_noElderBinding_elderIdIsNull_stillReachesBroker() throws Exception {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        String eventId = String.valueOf(IdWorker.getId());
        String sourceMessageId = "5B-C04-03-MSG-" + UUID.randomUUID();
        String traceId = "5b-c04-03-" + UUID.randomUUID();
        String deviceId = "5B-C04-03-MATTRESS-" + UUID.randomUUID();
        long dataTime = System.currentTimeMillis();

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        startVerificationConsumer(received, "\"eventId\":\"" + eventId + "\"");

        vitalSignProducer.send(vitalSign(eventId, sourceMessageId, deviceId, traceId, null,
                69, 15, 5, "OUT_OF_BED", dataTime));

        String body = awaitSingle(eventId, received, "C04-03 mattress delivery was not delivered to Broker");
        JsonNode payload = objectMapper.readTree(body).path("payload");
        assertTrue(payload.has("elder_id"), "C04-03 must serialize elder_id key even when null");
        assertTrue(payload.path("elder_id").isNull());

        log.info("C04-03 evidence: eventId={}, traceId={}, sourceMessageId={}, deviceId={}, "
                        + "elder_id=null, topic={}, tag={}, receivedBytes={}",
                eventId, traceId, sourceMessageId, deviceId,
                VITAL_TOPIC, VITAL_TAG, received.get(0).length());
    }

    private void startVerificationConsumer(CopyOnWriteArrayList<String> received, String match) throws Exception {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        verificationConsumer = new DefaultMQPushConsumer("C04-JOINT-VERIFY-" + UUID.randomUUID());
        verificationConsumer.setNamesrvAddr(nameServer);
        verificationConsumer.subscribe(VITAL_TOPIC, VITAL_TAG);
        verificationConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        verificationConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                if (body.contains(match)) {
                    received.add(body);
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        verificationConsumer.start();
    }

    private String awaitSingle(String eventId, CopyOnWriteArrayList<String> received, String message)
            throws InterruptedException {
        final String[] result = new String[1];
        await(() -> {
            for (String body : received) {
                if (body.contains("\"eventId\":\"" + eventId + "\"")) {
                    result[0] = body;
                    return true;
                }
            }
            return false;
        }, 30_000, message);
        return result[0];
    }

    private ParsedVitalSign vitalSign(String eventId, String sourceMessageId, String deviceId,
                                      String traceId, Long elderId,
                                      Integer heartRate, Integer respiratoryRate, Integer bodyMovement,
                                      String bedStatus, long dataTime) {
        return new ParsedVitalSign(
                eventId, sourceMessageId, deviceId,
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(dataTime), ZoneOffset.UTC),
                traceId, "1988123456789012301", "MATTRESS", elderId,
                heartRate, respiratoryRate, bodyMovement, bedStatus,
                Map.of("data_time", dataTime)
        );
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