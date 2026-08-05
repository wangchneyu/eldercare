package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Restores exactly one C05 failure probe through the real Broker. */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=false",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.recovery-probe.enabled", matches = "true")
class RocketMq5bRecoveryIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String PROBE_ID_PROPERTY = "iot.rocketmq.5b.probe-id";

    @Autowired
    private IotMqOutboxMapper outboxMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private String recoveredEventId;

    @BeforeAll
    static void requireSharedNameServerAndProbeId() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        String probeId = System.getProperty(PROBE_ID_PROPERTY);
        assertNotNull(nameServer,
                "recovery probe requires -Drocketmq.name-server=<shared-nameserver>:9876");
        assertTrue(!nameServer.isBlank()
                        && !nameServer.startsWith("127.")
                        && !nameServer.startsWith("localhost"),
                "recovery probe must target a non-loopback shared RocketMQ NameServer");
        assertTrue(probeId != null && !probeId.isBlank(),
                "recovery probe requires the failure probe's -Diot.rocketmq.5b.probe-id");
    }

    @AfterEach
    void cleanUpRecoveredProbe() {
        if (recoveredEventId != null) {
            outboxMapper.delete(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getEventId, recoveredEventId));
        }
    }

    @Test
    void c05_recoveryReusesOriginalEnvelopeAndMarksTheProbeSent() throws Exception {
        String probeId = System.getProperty(PROBE_ID_PROPERTY);
        IotMqOutbox pending = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                .eq(IotMqOutbox::getSourceMessageId, probeId));
        assertNotNull(pending, "the matching failure probe Outbox record was not found");
        assertNotNull(pending.getRawEnvelopeJson());
        recoveredEventId = pending.getEventId();
        String frozenEnvelope = pending.getRawEnvelopeJson();

        final IotMqOutbox[] recovered = new IotMqOutbox[1];
        await(() -> {
            recovered[0] = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getEventId, recoveredEventId));
            return recovered[0] != null && "SENT".equals(recovered[0].getStatus());
        }, 15_000, "C05 probe was not marked SENT by OutboxRetryTask through the real Broker");

        assertEquals(frozenEnvelope, recovered[0].getRawEnvelopeJson(),
                "recovery must send the frozen original C05 envelope without rebuilding it");
        JsonNode envelope = objectMapper.readTree(recovered[0].getRawEnvelopeJson());
        assertEquals(recoveredEventId, envelope.path("eventId").asText());
        assertEquals(probeId, envelope.path("payload").path("sourceMessageId").asText());
        log.info("5B C05 recovery confirmed: probeId={}, eventId={}, topic={}, tag={}, status={}",
                probeId, recoveredEventId, recovered[0].getTopic(), recovered[0].getTag(), recovered[0].getStatus());
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
