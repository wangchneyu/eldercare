package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.parser.model.ParsedSosEvent;
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
import java.time.OffsetDateTime;
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
 * Opt-in joint-test material for C05-04: the same raw C05 envelope is delivered
 * to the shared Broker twice - first via {@link OutboxService}, then replayed
 * byte-for-byte through the production {@link SosEventProducer} path - while the
 * eventId, traceId and sourceMessageId stay unchanged and no JSON is recomposed.
 * <p>
 * A real {@link DefaultMQPushConsumer} with an exclusive consumer group asserts the
 * Broker delivered exactly two identical messages for the generated eventId. Downstream
 * idempotency itself (only one alert for the same eventId) is confirmed by alert-service
 * (Sun Jie) from the emitted eventId; it is not claimed by this IoT-side test.
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=false",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.duplicate-replay.enabled", matches = "true")
class C05DuplicateReplay5bIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String C05_PARK_ID = "1988123456789012301";
    private static final String C05_LOCATION_BINDING_ID = "1988123456789012303";

    @Autowired
    private OutboxService outboxService;
    @Autowired
    private SosEventProducer sosEventProducer;
    @Autowired
    private IotMqOutboxMapper outboxMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private String createdEventId;
    private volatile DefaultMQPushConsumer verificationConsumer;

    @BeforeAll
    static void requireSharedNameServer() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        assertNotNull(nameServer,
                "C05 duplicate replay verification requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "C05 duplicate replay verification must target a non-loopback shared RocketMQ NameServer");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (verificationConsumer != null) {
            verificationConsumer.shutdown();
        }
        if (createdEventId != null) {
            outboxMapper.delete(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getEventId, createdEventId));
        }
    }

    @Test
    void c05_duplicateRawEnvelopeReplay_keepsEventIdAndBytesIdenticalAndReachesBrokerTwice() throws Exception {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        String eventId = String.valueOf(IdWorker.getId());
        String sourceMessageId = "5B-C05-DUP-" + UUID.randomUUID();
        String traceId = "5b-c05-dup-" + UUID.randomUUID();
        String deviceId = "5B-C05-DUP-DEVICE-" + UUID.randomUUID();
        createdEventId = eventId;

        // 前置订阅组：验证同一 eventId 的原始信封被 Broker 真实投递两次
        CopyOnWriteArrayList<String> receivedBodies = new CopyOnWriteArrayList<>();
        AtomicInteger receivedMatchingCount = new AtomicInteger();
        verificationConsumer = new DefaultMQPushConsumer("C05-DUP-REPLAY-VERIFY-" + UUID.randomUUID());
        verificationConsumer.setNamesrvAddr(nameServer);
        verificationConsumer.subscribe(MqTopicConstants.SOS_EVENT_TOPIC, MqTopicConstants.TAG_SOS);
        verificationConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        verificationConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                if (body.contains("\"eventId\":\"" + eventId + "\"")) {
                    receivedBodies.add(body);
                    receivedMatchingCount.incrementAndGet();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        verificationConsumer.start();

        // 首投：生产 Outbox 路径，eventId/traceId/sourceMessageId 由 IoT 首次生成
        outboxService.handleSosEvent(new ParsedSosEvent(
                eventId,
                sourceMessageId,
                deviceId,
                OffsetDateTime.now(),
                traceId,
                C05_PARK_ID,
                "SOS_BUTTON",
                "SOS_TRIGGERED",
                null,
                null,
                null,
                null,
                null,
                C05_LOCATION_BINDING_ID,
                Map.of("locationId", "5B-C05-DUP-LOC", "locationType", "PUBLIC_AREA",
                        "locationName", "C05 Duplicate Replay Test Location"),
                "BUTTON_PRESS",
                85,
                Map.of("testRun", "rocketmq-5b-duplicate-replay")
        ));

        final IotMqOutbox[] first = new IotMqOutbox[1];
        await(() -> {
            first[0] = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getEventId, eventId));
            return first[0] != null && "SENT".equals(first[0].getStatus());
        }, 15_000, "C05 first delivery did not reach SENT through the real Broker");

        IotMqOutbox outbox = first[0];
        String frozenEnvelope = outbox.getRawEnvelopeJson();
        assertNotNull(frozenEnvelope);
        assertEquals(MqTopicConstants.SOS_EVENT_TOPIC, outbox.getTopic());
        assertEquals(MqTopicConstants.TAG_SOS, outbox.getTag());
        assertEquals(eventId, outbox.getEventId());

        // 二次投递：同一 Outbox 记录的同一 rawEnvelopeJson 重放（生产重放路径，不生成新 eventId）
        sosEventProducer.send(outbox);

        IotMqOutbox afterReplay = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                .eq(IotMqOutbox::getEventId, eventId));
        assertNotNull(afterReplay);
        assertEquals(frozenEnvelope, afterReplay.getRawEnvelopeJson(),
                "replay must not rewrite the persisted raw envelope");
        assertEquals("SENT", afterReplay.getStatus());
        assertEquals(eventId, afterReplay.getEventId());

        // 断言 Broker 投递两次且字节级一致
        await(() -> receivedMatchingCount.get() >= 2, 30_000,
                "the real Broker did not deliver the duplicated envelope twice to the verification consumer");
        assertEquals(2, receivedBodies.size(),
                "exactly two identical deliveries are expected for the same eventId");
        assertEquals(receivedBodies.get(0), receivedBodies.get(1),
                "delivery #1 and delivery #2 must be byte-identical");
        assertEquals(frozenEnvelope, receivedBodies.get(0),
                "both deliveries must replay the frozen raw C05 envelope, not a recomposed JSON");

        JsonNode received = objectMapper.readTree(receivedBodies.get(0));
        assertEquals(eventId, received.path("eventId").asText(), "IoT must not generate a new eventId");
        assertEquals(traceId, received.path("traceId").asText());
        assertEquals(sourceMessageId, received.path("payload").path("sourceMessageId").asText());
        assertEquals("SOS_TRIGGERED", received.path("eventType").asText());
        assertEquals(C05_PARK_ID, received.path("payload").path("parkId").asText());
        assertEquals(C05_LOCATION_BINDING_ID, received.path("payload").path("locationBindingId").asText());

        log.info("C05 duplicate replay evidence: sendTime={}, topic={}, tag={}, eventId={}, traceId={}, "
                        + "sourceMessageId={}, parkId={}, locationBindingId={}, outboxStatus={}, "
                        + "deliveriesObserved={}, deliveriesByteIdentical=true, firstDeliveryBytes={}, "
                        + "replayUsesFrozenEnvelope=true, downstreamAlertIdempotency=PENDING-ALERT-SERVICE-CONFIRM",
                OffsetDateTime.now(), outbox.getTopic(), outbox.getTag(), eventId, traceId, sourceMessageId,
                C05_PARK_ID, C05_LOCATION_BINDING_ID, outbox.getStatus(), receivedBodies.size(),
                receivedBodies.get(0).length());
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