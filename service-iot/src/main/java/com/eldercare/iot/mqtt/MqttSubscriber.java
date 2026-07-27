package com.eldercare.iot.mqtt;

import com.eldercare.common.core.utils.IdUtil;
import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.parser.model.RawDeviceMessage;
import com.eldercare.iot.pipeline.DisruptorPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * MQTT 消息订阅器 — Phase 5 管线：
 * TopicRouter → MessageValidator → 构造 RawDeviceMessage → 生成 eventId → Disruptor RingBuffer 投递。
 * <p>
 * Paho 回调线程只做 Topic 路由、格式校验、信封元数据提取和 RingBuffer 投递，
 * 全程无 I/O（无 DB 查询、无网络调用）。
 */
@Slf4j
@Component
public class MqttSubscriber {

    private final MqttConnectionManager connectionManager;
    private final TopicRouter topicRouter;
    private final MessageValidator messageValidator;
    private final DisruptorPublisher disruptorPublisher;

    public MqttSubscriber(MqttConnectionManager connectionManager,
                          TopicRouter topicRouter,
                          MessageValidator messageValidator,
                          DisruptorPublisher disruptorPublisher) {
        this.connectionManager = connectionManager;
        this.topicRouter = topicRouter;
        this.messageValidator = messageValidator;
        this.disruptorPublisher = disruptorPublisher;
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
                return;
            }
            TopicRouter.RouteResult route = routeOpt.get();

            // ② 消息校验（无 I/O）
            Optional<JsonNode> envelopeOpt = messageValidator.validate(payload);
            if (envelopeOpt.isEmpty()) {
                return;
            }
            JsonNode envelope = envelopeOpt.get();

            // ③ 解析信封字段
            String messageId = envelope.get("messageId").asText();
            String deviceId = envelope.get("deviceId").asText();
            String messageType = envelope.get("messageType").asText();
            String protocolVersion = envelope.get("protocolVersion").asText();
            String occurredAtStr = envelope.get("occurredAt").asText();

            // ④ 生成 eventId（雪花 ID 十进制字符串）
            String eventId = String.valueOf(IdUtil.nextId());

            // ⑤ 解析 occurredAt
            OffsetDateTime occurredAt;
            try {
                occurredAt = OffsetDateTime.parse(occurredAtStr);
            } catch (Exception e) {
                log.warn("occurredAt 解析失败，使用当前时间: {}", occurredAtStr);
                occurredAt = OffsetDateTime.now();
            }

            // ⑥ 构造原始消息并投递到 Disruptor
            RawDeviceMessage rawMessage = new RawDeviceMessage(
                    topic,
                    envelope,
                    route.parkId(),
                    route.deviceType(),
                    deviceId,
                    messageType,
                    eventId,
                    traceId,
                    occurredAt
            );
            disruptorPublisher.publish(rawMessage);

            log.info("MQTT 消息已投递 Disruptor: eventId={}, messageId={}, deviceId={}, messageType={}, " +
                     "protocolVersion={}, traceId={}",
                    eventId, messageId, deviceId, messageType, protocolVersion, traceId);

        } catch (Exception e) {
            log.error("MQTT 消息处理异常: topic={}, traceId={}", topic, traceId, e);
        } finally {
            TraceContext.clear();
        }
    }
}
