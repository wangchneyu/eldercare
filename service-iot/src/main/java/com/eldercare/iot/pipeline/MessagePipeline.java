package com.eldercare.iot.pipeline;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.heartbeat.HeartbeatFlushTask;
import com.eldercare.iot.heartbeat.HeartbeatManager;
import com.eldercare.iot.mq.OutboxService;
import com.eldercare.iot.parser.model.ParsedEvent;
import com.eldercare.iot.parser.model.ParsedHeartbeat;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import com.eldercare.iot.parser.model.RawDeviceMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 消息处理管线入口：按事件类型路由到下游。
 * <p>
 * VITAL_SIGN → 体征聚合发送池
 * SOS/FALL   → P0 发送池（独立线程，不被体征洪峰阻塞）
 * HEARTBEAT  → 心跳管理器（Phase 6）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePipeline {

    private final VitalSignBatchAggregator vitalSignBatchAggregator;
    private final OutboxService outboxService;
    private final HeartbeatManager heartbeatManager;
    private final HeartbeatFlushTask heartbeatFlushTask;
    private final Executor iotP0Executor;

    public void handle(ParsedEvent event, RawDeviceMessage raw, Integer heartbeatTimeoutSeconds) {
        if (event == null) {
            return;
        }
        heartbeatManager.recordHeartbeat(event, heartbeatTimeoutSeconds);
        heartbeatFlushTask.requestFlushIfBatchReady();
        String traceId = event.traceId();
        if (event instanceof ParsedVitalSign) {
            vitalSignBatchAggregator.submit((ParsedVitalSign) event);
        } else if (event instanceof ParsedSosEvent sos) {
            try {
                iotP0Executor.execute(() -> {
                    try {
                        TraceContext.setTraceId(traceId);
                        outboxService.handleSosEvent(sos, raw != null ? raw.inboundMqttMessage() : null);
                    } finally {
                        TraceContext.clear();
                    }
                });
            } catch (RejectedExecutionException e) {
                // Do not acknowledge P0. MQTT redelivery will re-enter the Outbox transaction.
                log.warn("P0 executor is full; waiting for MQTT redelivery: eventId={}, traceId={}",
                        event.eventId(), traceId);
            }
        } else if (event instanceof ParsedHeartbeat) {
            // 心跳状态已在合法事件统一入口更新，无需额外下游动作。
        } else {
            log.warn("未知事件类型: {}", event.getClass().getSimpleName());
        }
    }
}
