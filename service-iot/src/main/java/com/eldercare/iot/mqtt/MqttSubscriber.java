package com.eldercare.iot.mqtt;

import com.eldercare.common.core.utils.IdUtil;
import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.metrics.IotMetrics;
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
 * TopicRouter → MessageValidator → Topic deviceId 与信封 deviceId 一致性校验 →
 * 构造 RawDeviceMessage（含 MQTT ack 凭证）→ 非阻塞 Disruptor 投递。
 * <p>
 * Paho 回调线程只做路由、校验、元数据提取和 RingBuffer 投递，全程无 I/O。
 * 若 RingBuffer 满，不阻塞回调线程，也不对 MQTT 做 ack，依赖 broker 重发。
 */
@Slf4j
@Component
public class MqttSubscriber {

    private final MqttConnectionManager connectionManager;
    private final TopicRouter topicRouter;
    private final MessageValidator messageValidator;
    private final DisruptorPublisher disruptorPublisher;
    private final IotMetrics metrics;

    public MqttSubscriber(MqttConnectionManager connectionManager,
                          TopicRouter topicRouter,
                          MessageValidator messageValidator,
                          DisruptorPublisher disruptorPublisher,
                          IotMetrics metrics) {
        this.connectionManager = connectionManager;
        this.topicRouter = topicRouter;
        this.messageValidator = messageValidator;
        this.disruptorPublisher = disruptorPublisher;
        this.metrics = metrics;
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
    private void handleMessage(InboundMqttMessage inbound) {
        String traceId = TraceContext.generateTraceId();
        TraceContext.setTraceId(traceId);
        try {
            String topic = inbound.topic();
            byte[] payload = inbound.payload();

            // ① Topic 路由
            Optional<TopicRouter.RouteResult> routeOpt = topicRouter.route(topic);
            if (routeOpt.isEmpty()) {
                rejectAndAck(inbound, "invalid_topic");
                return;
            }
            TopicRouter.RouteResult route = routeOpt.get();

            // ② 消息校验（无 I/O）
            Optional<JsonNode> envelopeOpt = messageValidator.validate(payload);
            if (envelopeOpt.isEmpty()) {
                rejectAndAck(inbound, "invalid_payload");
                return;
            }
            JsonNode envelope = envelopeOpt.get();

            // ③ 解析信封字段
            String messageId = envelope.get("messageId").asText();
            String deviceId = envelope.get("deviceId").asText();
            String messageType = envelope.get("messageType").asText();
            String protocolVersion = envelope.get("protocolVersion").asText();
            String occurredAtStr = envelope.get("occurredAt").asText();

            // ④ Topic deviceId 与信封 deviceId 一致性校验
            if (!route.deviceId().equals(deviceId)) {
                log.warn("Topic deviceId 与信封 deviceId 不一致: topicDeviceId={}, envelopeDeviceId={}, traceId={}",
                        route.deviceId(), deviceId, traceId);
                rejectAndAck(inbound, "device_id_mismatch");
                return;
            }

            // ⑤ 生成 eventId（雪花 ID 十进制字符串）
            String eventId = String.valueOf(IdUtil.nextId());

            // ⑥ 解析 occurredAt；非法时间戳直接拒绝，不得替换为当前时间
            OffsetDateTime occurredAt;
            try {
                occurredAt = OffsetDateTime.parse(occurredAtStr);
            } catch (Exception e) {
                log.warn("occurredAt 非法，拒绝消息: occurredAt={}, traceId={}", occurredAtStr, traceId);
                rejectAndAck(inbound, "invalid_occurred_at");
                return;
            }

            // ⑦ 构造原始消息并投递到 Disruptor
            RawDeviceMessage rawMessage = new RawDeviceMessage(
                    topic,
                    envelope,
                    route.parkId(),
                    route.deviceType(),
                    deviceId,
                    messageType,
                    eventId,
                    traceId,
                    occurredAt,
                    inbound.messageId(),
                    inbound.qos(),
                    inbound
            );
            boolean accepted = disruptorPublisher.publish(rawMessage);
            if (!accepted) {
                // RingBuffer 满：不阻塞回调，不 ack MQTT，依赖 broker 重发
                log.warn("Disruptor RingBuffer 已满，拒绝入站消息: deviceId={}, traceId={}", deviceId, traceId);
                metrics.ringBufferRejected();
                return;
            }

            metrics.mqttMessageReceived(route.deviceType());
            log.info("MQTT 消息已投递 Disruptor: eventId={}, messageId={}, deviceId={}, messageType={}, " +
                     "protocolVersion={}, traceId={}, mqttMessageId={}, mqttQos={}",
                    eventId, messageId, deviceId, messageType, protocolVersion, traceId,
                    inbound.messageId(), inbound.qos());

        } catch (Exception e) {
            log.error("MQTT 消息处理异常: traceId={}", traceId, e);
        } finally {
            TraceContext.clear();
        }
    }

    /** Terminal input errors must be acknowledged to prevent infinite QoS redelivery. */
    private void rejectAndAck(InboundMqttMessage inbound, String reason) {
        metrics.mqttMessageRejected(reason);
        if (inbound.requiresAck()) {
            inbound.ack();
            metrics.mqttMessageAcked(String.valueOf(inbound.qos()));
        }
    }
}
