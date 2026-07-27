package com.eldercare.iot.pipeline;

import com.eldercare.common.core.utils.TraceContext;
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
    private final Executor iotP0Executor;

    public void handle(ParsedEvent event, RawDeviceMessage raw) {
        if (event == null) {
            return;
        }
        String traceId = event.traceId();
        if (event instanceof ParsedVitalSign) {
            vitalSignBatchAggregator.submit((ParsedVitalSign) event);
        } else if (event instanceof ParsedSosEvent sos) {
            iotP0Executor.execute(() -> {
                try {
                    TraceContext.setTraceId(traceId);
                    outboxService.handleSosEvent(sos, raw != null ? raw.inboundMqttMessage() : null);
                } finally {
                    TraceContext.clear();
                }
            });
        } else if (event instanceof ParsedHeartbeat) {
            // Phase 6 心跳管理器接入点
            log.debug("心跳消息暂存: deviceId={}", ((ParsedHeartbeat) event).deviceId());
        } else {
            log.warn("未知事件类型: {}", event.getClass().getSimpleName());
        }
    }
}
