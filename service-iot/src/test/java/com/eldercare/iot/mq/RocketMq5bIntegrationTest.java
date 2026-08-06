package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in 5B verification against a real RocketMQ NameServer and Broker.
 *
 * <p>The class deliberately does not import {@code IntegrationTestConfig}: the
 * {@link org.apache.rocketmq.spring.core.RocketMQTemplate} is real. Keep the
 * default test suite isolated by requiring both the JUnit opt-in switch and a
 * non-loopback {@code rocketmq.name-server} system property.</p>
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=false",
        "iot.outbox.retry.fixed-delay-ms=60000",
        "iot.vital-delivery.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.enabled", matches = "true")
class RocketMq5bIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String C04_TAG = "MATTRESS";

    @Autowired
    private VitalSignProducer vitalSignProducer;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private IotMqOutboxMapper outboxMapper;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ObjectMapper objectMapper;

    private String createdC05EventId;

    @BeforeAll
    static void requireSharedNameServer() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        assertNotNull(nameServer,
                "5B verification requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "5B verification must target a non-loopback shared RocketMQ NameServer");
    }

    @AfterEach
    void cleanUpC05Outbox() {
        if (createdC05EventId != null) {
            outboxMapper.delete(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getEventId, createdC05EventId));
        }
    }

    @Test
    void c04_vitalSign_isAcceptedByRealBrokerWithFrozenTopicAndTag() throws Exception {
        double successBefore = mqSuccessCount(MqTopicConstants.VITAL_SIGN_TOPIC, C04_TAG);
        String eventId = String.valueOf(IdWorker.getId());
        String sourceMessageId = "5B-C04-" + UUID.randomUUID();
        String traceId = "5b-c04-" + UUID.randomUUID();

        vitalSignProducer.send(new ParsedVitalSign(
                eventId,
                sourceMessageId,
                "5B-C04-DEVICE-" + UUID.randomUUID(),
                OffsetDateTime.now(),
                traceId,
                "P001",
                C04_TAG,
                2001L,
                72,
                18,
                3,
                "IN_BED",
                Map.of("testRun", "rocketmq-5b")
        ));

        await(() -> mqSuccessCount(MqTopicConstants.VITAL_SIGN_TOPIC, C04_TAG) > successBefore,
                15_000,
                "C04 did not receive a real RocketMQ SEND_OK callback");

        log.info("5B C04 confirmed: eventId={}, traceId={}, topic={}, tag={}, nameServer={}",
                eventId, traceId, MqTopicConstants.VITAL_SIGN_TOPIC, C04_TAG,
                System.getProperty(NAME_SERVER_PROPERTY));
    }

    @Test
    void c05_sos_isAcceptedByRealBrokerAndMarksOutboxSentWithOriginalEnvelope() throws Exception {
        createdC05EventId = String.valueOf(IdWorker.getId());
        String sourceMessageId = "5B-C05-" + UUID.randomUUID();
        String traceId = "5b-c05-" + UUID.randomUUID();
        String deviceId = "5B-C05-DEVICE-" + UUID.randomUUID();

        outboxService.handleSosEvent(new ParsedSosEvent(
                createdC05EventId,
                sourceMessageId,
                deviceId,
                OffsetDateTime.now(),
                traceId,
                "1988123456789012301",
                "SOS_BUTTON",
                "SOS_TRIGGERED",
                null,
                null,
                null,
                null,
                null,
                "1988123456789012303",
                Map.of("locationId", "5B-LOCATION", "locationType", "PUBLIC_AREA", "locationName", "RocketMQ 5B Test"),
                "BUTTON_PRESS",
                85,
                Map.of("testRun", "rocketmq-5b")
        ));

        final IotMqOutbox[] persisted = new IotMqOutbox[1];
        await(() -> {
            persisted[0] = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getEventId, createdC05EventId));
            return persisted[0] != null && "SENT".equals(persisted[0].getStatus());
        }, 15_000, "C05 Outbox was not marked SENT after real RocketMQ SEND_OK");

        IotMqOutbox outbox = persisted[0];
        assertEquals(MqTopicConstants.SOS_EVENT_TOPIC, outbox.getTopic());
        assertEquals(MqTopicConstants.TAG_SOS, outbox.getTag());
        assertNotNull(outbox.getSentAt());
        assertNotNull(outbox.getRawEnvelopeJson());

        JsonNode envelope = objectMapper.readTree(outbox.getRawEnvelopeJson());
        assertEquals(createdC05EventId, envelope.path("eventId").asText());
        assertEquals(traceId, envelope.path("traceId").asText());
        assertEquals(sourceMessageId, envelope.path("payload").path("sourceMessageId").asText());
        assertEquals("SOS_TRIGGERED", envelope.path("eventType").asText());

        log.info("5B C05 confirmed: eventId={}, traceId={}, sourceMessageId={}, topic={}, tag={}, status={}",
                createdC05EventId, traceId, sourceMessageId, outbox.getTopic(), outbox.getTag(), outbox.getStatus());
    }

    private double mqSuccessCount(String topic, String tag) {
        Counter counter = meterRegistry.find("iot_mq_send_total")
                .tags("topic", topic, "tag", tag, "status", "success")
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
