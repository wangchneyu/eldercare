package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Creates one recoverable C05 failure using an intentionally unreachable
 * NameServer. Run the matching recovery test with the same probe id after it.
 */
@Slf4j
@SpringBootTest(properties = {
        "mqtt.auto-connect=false",
        "iot.outbox.retry.fixed-delay-ms=60000"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "iot.rocketmq.5b.failure-probe.enabled", matches = "true")
class RocketMq5bFailureProbeIntegrationTest {

    private static final String NAME_SERVER_PROPERTY = "rocketmq.name-server";
    private static final String PROBE_ID_PROPERTY = "iot.rocketmq.5b.probe-id";

    @Autowired
    private OutboxService outboxService;
    @Autowired
    private IotMqOutboxMapper outboxMapper;

    @BeforeAll
    static void requireFailureTargetAndProbeId() {
        String nameServer = System.getProperty(NAME_SERVER_PROPERTY);
        String probeId = System.getProperty(PROBE_ID_PROPERTY);
        assertNotNull(nameServer, "failure probe requires -Drocketmq.name-server=<unreachable-host>:port");
        assertTrue(!nameServer.isBlank() && !nameServer.endsWith(":9876"),
                "failure probe must not target the shared RocketMQ NameServer port");
        assertTrue(probeId != null && !probeId.isBlank(),
                "failure probe requires a unique -Diot.rocketmq.5b.probe-id");
    }

    @Test
    void c05_failureRemainsPendingWithFrozenEnvelopeForLaterRealBrokerRecovery() throws Exception {
        String probeId = System.getProperty(PROBE_ID_PROPERTY);
        String eventId = String.valueOf(IdWorker.getId());
        String traceId = "5b-c05-failure-" + probeId;

        outboxService.handleSosEvent(new ParsedSosEvent(
                eventId,
                probeId,
                "5B-C05-FAILURE-" + probeId,
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
                Map.of("locationId", "5B-LOCATION", "locationType", "PUBLIC_AREA", "locationName", "RocketMQ 5B Failure Probe"),
                "BUTTON_PRESS",
                85,
                Map.of("testRun", "rocketmq-5b-failure-probe")
        ));

        final IotMqOutbox[] persisted = new IotMqOutbox[1];
        await(() -> {
            persisted[0] = outboxMapper.selectOne(new LambdaQueryWrapper<IotMqOutbox>()
                    .eq(IotMqOutbox::getSourceMessageId, probeId));
            return persisted[0] != null
                    && "PENDING".equals(persisted[0].getStatus())
                    && persisted[0].getRetryCount() >= SosEventProducer.MAX_FRONT_RETRIES;
        }, 20_000, "controlled C05 send failure did not remain PENDING after foreground retries");

        assertNotNull(persisted[0].getRawEnvelopeJson());
        assertEquals(eventId, persisted[0].getEventId());
        log.info("5B C05 failure probe confirmed: probeId={}, eventId={}, traceId={}, status={}, retryCount={}",
                probeId, eventId, traceId, persisted[0].getStatus(), persisted[0].getRetryCount());
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
