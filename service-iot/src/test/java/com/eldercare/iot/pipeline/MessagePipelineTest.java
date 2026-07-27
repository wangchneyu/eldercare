package com.eldercare.iot.pipeline;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.mq.OutboxService;
import com.eldercare.iot.parser.model.ParsedHeartbeat;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MessagePipeline 单元测试：验证 VITAL_SIGN / SOS / FALL / HEARTBEAT 的事件路由与 traceId 透传。
 */
@ExtendWith(MockitoExtension.class)
class MessagePipelineTest {

    @Mock
    VitalSignBatchAggregator vitalSignBatchAggregator;
    @Mock
    OutboxService outboxService;
    @Mock
    Executor iotP0Executor;

    @InjectMocks
    MessagePipeline pipeline;

    @Test
    void nullEvent_isIgnored() {
        pipeline.handle(null, null);
        verifyNoInteractions(vitalSignBatchAggregator, outboxService, iotP0Executor);
    }

    @Test
    void vitalSign_routesToAggregator() {
        ParsedVitalSign vs = vitalSign();
        pipeline.handle(vs, null);
        verify(vitalSignBatchAggregator).submit(vs);
        verifyNoInteractions(outboxService);
    }

    @Test
    void sosEvent_routesToP0ExecutorWithTraceId() {
        ParsedSosEvent sos = sosEvent("SOS_TRIGGERED");
        // 同步执行，便于断言 traceId 在回调线程中被设置
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotP0Executor).execute(any(Runnable.class));
        doAnswer(invocation -> {
            assertEquals("trace-sos", TraceContext.currentTraceId());
            return null;
        }).when(outboxService).handleSosEvent(any());

        pipeline.handle(sos, null);

        verify(iotP0Executor).execute(any(Runnable.class));
        verify(outboxService).handleSosEvent(sos);
    }

    @Test
    void fallEvent_routesToP0Executor() {
        ParsedSosEvent fall = sosEvent("FALL_DETECTED");
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(iotP0Executor).execute(any(Runnable.class));

        pipeline.handle(fall, null);

        verify(outboxService).handleSosEvent(fall);
    }

    @Test
    void heartbeat_isLoggedNotRouted() {
        ParsedHeartbeat hb = new ParsedHeartbeat(
                "EVT", "msg", "DEV", OffsetDateTime.now(), "trace", "P001", "RADAR");
        pipeline.handle(hb, null);
        verifyNoInteractions(vitalSignBatchAggregator, outboxService);
    }

    private ParsedVitalSign vitalSign() {
        return new ParsedVitalSign(
                "EVT", "msg", "DEV", OffsetDateTime.now(), "trace",
                "P001", "MATTRESS", null, 75, 16, 1, "IN_BED", null);
    }

    private ParsedSosEvent sosEvent(String eventType) {
        return new ParsedSosEvent(
                "EVT", "msg", "DEV", OffsetDateTime.now(), "trace-sos",
                "P001", "SOS_BUTTON", eventType,
                null, null, null, null, null, null, null, "PRESS", 85, null);
    }
}
