package com.eldercare.iot.mq;

import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PostgreSQL 集成测试：验证 OutboxService 的重复去重、首发/补发等价性与并发状态保护。
 * <p>
 * 依赖本地 PostgreSQL，未启动数据库时本测试会失败；RocketMQTemplate 为 mock，不依赖真实 RocketMQ。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
class OutboxServiceIntegrationTest {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private SosEventProducer sosEventProducer;

    @Autowired
    private IotMqOutboxMapper outboxMapper;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetRocketMQMock() {
        reset(rocketMQTemplate);
        SendResult ok = new SendResult();
        ok.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(ok);
    }

    @Test
    void duplicateEvent_isIgnoredAndProducerNotCalled() {
        ParsedSosEvent event = sosEvent("SOS_TRIGGERED");

        outboxService.handleSosEvent(event);
        outboxService.handleSosEvent(event);

        verify(rocketMQTemplate, times(1)).syncSend(anyString(), any(Message.class), anyLong());
    }

    @Test
    void firstSendAndRetry_areByteLevelEquivalent() throws Exception {
        ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
        outboxService.handleSosEvent(event);

        IotMqOutbox outbox = outboxMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotMqOutbox>()
                        .eq(IotMqOutbox::getEventId, event.eventId())
        );
        assertNotNull(outbox);
        assertNotNull(outbox.getRawEnvelope());
        assertNotNull(outbox.getRawEnvelopeJson());

        // 补发：同一 Outbox 记录直接交给生产者
        sosEventProducer.send(outbox);

        // 抓取两次 syncSend 的 Message payload
        org.mockito.ArgumentCaptor<Message<String>> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate, times(2)).syncSend(eq("elder-sos-event:SOS"), captor.capture(), eq(3000L));
        String firstJson = captor.getAllValues().get(0).getPayload();
        String secondJson = captor.getAllValues().get(1).getPayload();

        assertEquals(firstJson, secondJson);
        assertEquals(outbox.getRawEnvelopeJson(), firstJson);

        outboxMapper.deleteById(outbox.getId());
    }

    @Test
    void concurrentMarkSentAndFailure_doesNotOverwriteSentBackToPending() throws InterruptedException {
        ParsedSosEvent event = sosEvent("SOS_TRIGGERED");
        outboxService.handleSosEvent(event);

        IotMqOutbox outbox = outboxMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotMqOutbox>()
                        .eq(IotMqOutbox::getEventId, event.eventId())
        );
        assertNotNull(outbox);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger sentSuccess = new AtomicInteger(0);
        AtomicInteger failureSuccess = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final boolean markSent = i % 2 == 0;
            executor.submit(() -> {
                try {
                    start.await();
                    if (markSent) {
                        if (outboxService.markSent(outbox.getEventId(), outbox.getClaimedBy())) {
                            sentSuccess.incrementAndGet();
                        }
                    } else {
                        if (outboxService.recordFailure(outbox.getEventId(), 1, "timeout", outbox.getClaimedBy())) {
                            failureSuccess.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        IotMqOutbox updated = outboxMapper.selectById(outbox.getId());
        // 并发条件下：要么 SENT，要么 PENDING；不允许 SENT 被覆盖回 PENDING
        assertTrue(
                OutboxStatus.SENT.getCode().equals(updated.getStatus())
                        || OutboxStatus.PENDING.getCode().equals(updated.getStatus()),
                "最终状态只能是 SENT 或 PENDING: " + updated.getStatus()
        );
        // 一旦为 SENT，失败路径不应再成功覆盖
        if (OutboxStatus.SENT.getCode().equals(updated.getStatus())) {
            assertEquals(0, failureSuccess.get(), "SENT 后 recordFailure 不应再成功");
        }

        outboxMapper.deleteById(outbox.getId());
    }

    private ParsedSosEvent sosEvent(String eventType) {
        String eventId = "EVT-" + UUID.randomUUID();
        String deviceId = "DEV-" + UUID.randomUUID();
        String sourceMessageId = "MSG-" + UUID.randomUUID();
        return new ParsedSosEvent(
                eventId, sourceMessageId, deviceId,
                OffsetDateTime.parse("2026-07-24T02:30:00Z"),
                "trace-" + UUID.randomUUID(),
                "1988123456789012301", "SOS_BUTTON", eventType,
                42L, "1988123456789012304", "1988123456789012302", "R001", "301",
                "1988123456789012303",
                Map.of("locationId", "LOC-001", "locationType", "PUBLIC_AREA", "locationName", "Test Area"),
                "BUTTON_PRESS", 85, null
        );
    }
}
