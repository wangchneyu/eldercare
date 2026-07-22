package com.eldercare.common.core.mq;

import com.eldercare.common.core.domain.BaseEvent;
import com.eldercare.common.core.utils.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;

/**
 * MQ 生产者封装
 * <p>
 * 封装 RocketMQ DefaultMQProducer，提供同步/异步/延迟/事务四种发送模式。
 * 业务服务注入本类后传入 BaseEvent 子类即可发送消息，无需关心底层细节。
 * <p>
 * 消息规范:
 * <ul>
 *   <li>消息体: BaseEvent JSON 序列化</li>
 *   <li>消息 Key: eventId（支持顺序消息和幂等键）</li>
 *   <li>User Properties: X-Trace-Id（供消费方在反序列化前即可关联链路）</li>
 * </ul>
 */
@Slf4j
public class MQProducer {

    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;

    public MQProducer(DefaultMQProducer producer, ObjectMapper objectMapper) {
        this.producer = producer;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步发送消息（失败自动重试 2 次，RocketMQ SDK 内置）
     *
     * @param topic 目标 Topic
     * @param event BaseEvent 子类实例
     * @return SendResult
     */
    public SendResult send(String topic, BaseEvent<?> event) {
        Message message = buildMessage(topic, event);
        try {
            SendResult result = producer.send(message);
            log.info("MQ 同步发送成功: topic={}, eventId={}, msgId={}", topic, event.getEventId(), result.getMsgId());
            return result;
        } catch (Exception e) {
            log.error("MQ 同步发送失败: topic={}, eventId={}", topic, event.getEventId(), e);
            throw new RuntimeException("MQ 消息发送失败: " + topic, e);
        }
    }

    /**
     * 异步发送消息（失败回调记录日志）
     */
    public void sendAsync(String topic, BaseEvent<?> event) {
        Message message = buildMessage(topic, event);
        try {
            producer.send(message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.info("MQ 异步发送成功: topic={}, eventId={}, msgId={}", topic, event.getEventId(), sendResult.getMsgId());
                }

                @Override
                public void onException(Throwable e) {
                    log.error("MQ 异步发送失败: topic={}, eventId={}", topic, event.getEventId(), e);
                }
            });
        } catch (Exception e) {
            log.error("MQ 异步发送提交失败: topic={}, eventId={}", topic, event.getEventId(), e);
        }
    }

    /**
     * 延迟发送消息
     *
     * @param delayLevel RocketMQ 延迟级别（1=1s, 2=5s, 3=10s, 4=30s, 5=1m, ...）
     */
    public SendResult sendDelay(String topic, BaseEvent<?> event, int delayLevel) {
        Message message = buildMessage(topic, event);
        message.setDelayTimeLevel(delayLevel);
        try {
            SendResult result = producer.send(message);
            log.info("MQ 延迟发送成功: topic={}, eventId={}, delayLevel={}", topic, event.getEventId(), delayLevel);
            return result;
        } catch (Exception e) {
            log.error("MQ 延迟发送失败: topic={}, eventId={}", topic, event.getEventId(), e);
            throw new RuntimeException("MQ 延迟消息发送失败: " + topic, e);
        }
    }

    /**
     * 发送事务消息（半消息 + 本地事务执行 + 回查）
     * <p>
     * 当前版本暂未实现，需要 TransactionMQProducer 支持。
     *
     * @throws UnsupportedOperationException 始终抛出，提示使用其他发送模式
     */
    public TransactionSendResult sendTransaction(String topic, BaseEvent<?> event, TransactionListener listener) {
        throw new UnsupportedOperationException(
                "事务消息需要 TransactionMQProducer 支持，当前版本暂未实现。请使用 send()/sendAsync()/sendDelay() 替代");
    }

    /**
     * 构建 RocketMQ Message
     */
    private Message buildMessage(String topic, BaseEvent<?> event) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(event);
            Message message = new Message(topic, body);
            // 消息 Key = eventId（支持幂等键和消息追踪）
            message.setKeys(event.getEventId());
            // traceId 写入 User Properties（供消费方在反序列化前即可关联链路）
            String traceId = TraceContext.currentTraceId();
            if (traceId != null && !traceId.isEmpty()) {
                message.putUserProperty("X-Trace-Id", traceId);
            }
            return message;
        } catch (Exception e) {
            throw new RuntimeException("MQ 消息序列化失败: eventId=" + event.getEventId(), e);
        }
    }
}
