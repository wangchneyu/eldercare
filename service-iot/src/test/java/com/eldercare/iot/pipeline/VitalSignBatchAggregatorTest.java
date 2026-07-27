package com.eldercare.iot.pipeline;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.mq.VitalSignProducer;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class VitalSignBatchAggregatorTest {

    @Mock
    VitalSignProducer vitalSignProducer;
    @Mock
    Executor iotMqExecutor;
    @Mock
    ScheduledExecutorService iotVitalFlushScheduler;

    VitalSignBatchAggregator aggregator;

    @BeforeEach
    void init() {
        aggregator = new VitalSignBatchAggregator(vitalSignProducer, iotMqExecutor, iotVitalFlushScheduler);
        aggregator.init();
    }

    @Test
    void submit_addsToBufferWithoutImmediateSend() {
        aggregator.submit(vitalSign("EVT-1"));
        verify(vitalSignProducer, never()).send(any());
    }

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

    @Test
    void thresholdFlush_sendsAllBufferedEvents() {
        doAnswer(invocation -> {
            System.out.println("iotMqExecutor.execute called");
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotMqExecutor).execute(any(Runnable.class));

        ParsedVitalSign first = vitalSign("EVT-1");
        ParsedVitalSign second = vitalSign("EVT-2");
        aggregator.submit(first);
        aggregator.submit(second);
        // 触发 500 条阈值：已提交 2 条，再补充 498 条（从 EVT-3 开始避免重复）
        for (int i = 3; i <= 500; i++) {
            aggregator.submit(vitalSign("EVT-" + i));
        }

        verify(vitalSignProducer).send(first);
        verify(vitalSignProducer, times(1)).send(second);
    }

    @Test
    void flush_restoresPreviousTraceId() {
        System.out.println("flush_restoresPreviousTraceId: test iotMqExecutor=" + System.identityHashCode(iotMqExecutor));
        doAnswer(invocation -> {
            System.out.println("flush_restoresPreviousTraceId: iotMqExecutor.execute called");
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotMqExecutor).execute(any(Runnable.class));

        TraceContext.setTraceId("previous-trace");
        ParsedVitalSign vs = vitalSign("EVT-1");
        aggregator.submit(vs);
        // 补充到 500 条，避免与初始 EVT-1 重复
        for (int i = 2; i <= 500; i++) {
            aggregator.submit(vitalSign("EVT-" + i));
        }
        System.out.println("flush_restoresPreviousTraceId: submitted 500 events");

        assertEquals("previous-trace", TraceContext.currentTraceId());
        TraceContext.clear();
    }

    private ParsedVitalSign vitalSign(String eventId) {
        return new ParsedVitalSign(
                eventId, "msg", "DEV", OffsetDateTime.now(), "trace-" + eventId,
                "P001", "MATTRESS", 1L, 75, 16, 1, "IN_BED", null);
    }
}
