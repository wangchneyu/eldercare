package com.eldercare.iot.mqtt;

import com.eldercare.common.core.utils.IdUtil;
import com.eldercare.common.core.utils.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * MQTT 消息订阅器 — Phase 4 管线：
 * TopicRouter → MessageValidator → 解析信封 → 生成 eventId → 日志输出
 * <p>
 * 全程无 I/O（无 DB 查询、无网络调用）。
 * Phase 5 将在此处添加 Disruptor RingBuffer 投递。
 */
@Slf4j
@Component
public class MqttSubscriber {

    private final MqttConnectionManager connectionManager;
    private final TopicRouter topicRouter;
    private final MessageValidator messageValidator;

    public MqttSubscriber(MqttConnectionManager connectionManager,
                          TopicRouter topicRouter,
                          MessageValidator messageValidator) {
        this.connectionManager = connectionManager;
        this.topicRouter = topicRouter;
        this.messageValidator = messageValidator;
    }

    @PostConstruct
    public void init() {
        connectionManager.setMessageHandler(this::handleMessage);
        connectionManager.connect();
    }

    @PreDestroy
    public void destroy() {
        connectionManager.disconnect();
    }

    /**
     * Paho 回调入口 — 不执行任何 I/O。
     */
    private void handleMessage(String topic, byte[] payload) {
        String traceId = TraceContext.generateTraceId();
        TraceContext.setTraceId(traceId);
        try {
            // ① Topic 路由
            Optional<TopicRouter.RouteResult> routeOpt = topicRouter.route(topic);
            if (routeOpt.isEmpty()) {
                return; // TopicRouter 已 log.warn
            }
            TopicRouter.RouteResult route = routeOpt.get();
            log.debug("Topic 解析: parkId={}, deviceType={}, deviceId={}, messageType={}",
                    route.parkId(), route.deviceType(), route.deviceId(), route.messageType());

            // ② 消息校验（无 I/O）
            Optional<JsonNode> envelopeOpt = messageValidator.validate(payload);
            if (envelopeOpt.isEmpty()) {
                return; // MessageValidator 已 log.warn
            }
            JsonNode envelope = envelopeOpt.get();

            // ③ 解析信封字段
            String messageId = envelope.get("messageId").asText();
            String deviceId = envelope.get("deviceId").asText();
            String messageType = envelope.get("messageType").asText();
            String protocolVersion = envelope.get("protocolVersion").asText();
            String occurredAtStr = envelope.get("occurredAt").asText();

            // ④ 生成 eventId（雪花 ID）
            String eventId = String.valueOf(IdUtil.nextId());

            // ⑤ 解析 occurredAt
            OffsetDateTime occurredAt;
            try {
                occurredAt = OffsetDateTime.parse(occurredAtStr);
            } catch (Exception e) {
                log.warn("occurredAt 解析失败: {}", occurredAtStr);
                occurredAt = OffsetDateTime.now();
            }

            log.info("消息处理完成: eventId={}, messageId={}, deviceId={}, messageType={}, " +
                     "protocolVersion={}, traceId={}",
                    eventId, messageId, deviceId, messageType, protocolVersion, traceId);

            // Phase 5: 构建 RawDeviceMessage 并投递到 Disruptor RingBuffer
            // var rawMessage = new RawDeviceMessage(
            //     topic, envelope, route.parkId(), route.deviceType(),
            //     route.deviceId(), route.messageType(), eventId, traceId, occurredAt);
            // disruptorPublisher.publish(rawMessage);

        } catch (Exception e) {
            log.error("消息处理异常: topic={}, traceId={}", topic, traceId, e);
        } finally {
            TraceContext.clear();
        }
    }
}
