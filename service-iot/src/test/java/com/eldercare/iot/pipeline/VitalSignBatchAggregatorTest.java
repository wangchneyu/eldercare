package com.eldercare.iot.pipeline;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.mq.VitalSignProducer;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * VitalSignBatchAggregator 单元测试：验证缓冲、阈值 flush 与定时 flush。
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VitalSignBatchAggregatorTest {

    @Mock
    VitalSignProducer vitalSignProducer;
    @Mock
    Executor iotMqExecutor;
    @Mock
    ScheduledExecutorService iotVitalFlushScheduler;

    @InjectMocks
    VitalSignBatchAggregator aggregator;

    @BeforeEach
    void init() {
        aggregator.init();
    }

    @org.junit.jupiter.api.Order(1)
    @Test
    void submit_addsToBufferWithoutImmediateSend() {
        aggregator.submit(vitalSign("EVT-1"));
        verify(vitalSignProducer, never()).send(any());
    }

    @org.junit.jupiter.api.Order(2)
    @Test
    void scheduledFlush_runsPeriodicallyAndSendsBufferedEvents() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(iotVitalFlushScheduler).scheduleAtFixedRate(
                captor.capture(), eq(1000L), eq(1000L), eq(TimeUnit.MILLISECONDS));

        ParsedVitalSign vs = vitalSign("EVT-1");
        aggregator.submit(vs);
        captor.getValue().run();

        verify(vitalSignProducer).send(vs);
    }

    @org.junit.jupiter.api.Order(3)
    @Test
    void thresholdFlush_sendsAllBufferedEvents() {
        // 为避免 @InjectMocks 实例在多测试间状态干扰，单独构造全新聚合器
        VitalSignProducer producer = mock(VitalSignProducer.class);
        Executor executor = mock(Executor.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        VitalSignBatchAggregator isolated = new VitalSignBatchAggregator(producer, executor, scheduler);
        isolated.init();

        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ParsedVitalSign first = vitalSign("EVT-1");
        ParsedVitalSign second = vitalSign("EVT-2");
        isolated.submit(first);
        isolated.submit(second);
        for (int i = 3; i <= 500; i++) {
            isolated.submit(vitalSign("EVT-" + i));
        }

        verify(executor, atLeastOnce()).execute(any(Runnable.class));
        verify(producer, atLeastOnce()).send(any(ParsedVitalSign.class));
        verify(producer).send(first);
        verify(producer, times(1)).send(second);
    }

    @org.junit.jupiter.api.Order(4)
    @Test
    void flush_restoresPreviousTraceId() {
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotMqExecutor).execute(any(Runnable.class));

        TraceContext.setTraceId("previous-trace");
        ParsedVitalSign vs = vitalSign("EVT-1");
        aggregator.submit(vs);
        for (int i = 2; i <= 500; i++) {
            aggregator.submit(vitalSign("EVT-" + i));
        }

        assertEquals("previous-trace", TraceContext.currentTraceId());
        TraceContext.clear();
    }

    private ParsedVitalSign vitalSign(String eventId) {
        return new ParsedVitalSign(
                eventId, "msg", "DEV", OffsetDateTime.now(), "trace-" + eventId,
                "P001", "MATTRESS", 1L, 75, 16, 1, "IN_BED", null);
    }
}
