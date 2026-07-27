package com.eldercare.common.core.mq;

import com.eldercare.common.core.domain.BaseEvent;
import com.eldercare.common.core.utils.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;

/**
 * MQ 消费者抽象基类
 * <p>
 * 封装 RocketMQ 消费逻辑，提供:
 * <ul>
 *   <li>自动反序列化 BaseEvent</li>
 *   <li>双层幂等: Redis 快速拦截 (TTL 24h) + DB mq_consumed 表唯一约束</li>
 *   <li>traceId 提取与 MDC 设置</li>
 *   <li>消费日志（topic/tag/eventId/traceId/耗时）</li>
 * </ul>
 * <p>
 * 子类只需实现 {@link #processEvent(BaseEvent)} 方法处理业务逻辑。
 * 消费失败抛异常后 RocketMQ 自动重试 16 次，超过后进入死信队列。
 * <p>
 * traceId 提取优先级:
 * <ol>
 *   <li>Message Header (user properties X-Trace-Id，由生产方写入)</li>
 *   <li>消息体信封的 traceId 字段</li>
 *   <li>两者皆无则生成新 traceId 并记 WARN 日志</li>
 * </ol>
 *
 * @param <T> BaseEvent 子类类型
 */
@Slf4j
public abstract class BaseConsumer<T extends BaseEvent<?>> {

    protected final ObjectMapper objectMapper;

    protected BaseConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 消费入口（由 RocketMQ MessageListener 调用）
     *
     * @param messageExt RocketMQ 原始消息
     */
    public void onMessage(MessageExt messageExt) {
        long startTime = System.currentTimeMillis();
        String topic = messageExt.getTopic();
        String tag = messageExt.getTags();
        String msgId = messageExt.getMsgId();

        // 1. 提取 traceId（优先从 Message Header）
        String traceId = messageExt.getUserProperty("X-Trace-Id");
        boolean traceIdFromHeader = traceId != null && !traceId.isEmpty();

        try {
            // 2. 反序列化
            String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            T event = (T) objectMapper.readValue(body, getEventClass());

            // 回退: 从消息体信封提取 traceId
            if (!traceIdFromHeader) {
                traceId = event.getTraceId();
            }
            // 两者皆无则生成新 traceId
            if (traceId == null || traceId.isEmpty()) {
                traceId = TraceContext.generateTraceId();
                log.warn("MQ 消费消息无 traceId，生成新值: topic={}, eventId={}, newTraceId={}", topic, event.getEventId(), traceId);
            }

            // 3. 设置 MDC
            TraceContext.setTraceId(traceId);

            String eventId = event.getEventId();
            log.info("MQ 消费开始: topic={}, tag={}, eventId={}, traceId={}", topic, tag, eventId, traceId);

            // 4. 幂等检查（子类可覆盖 isDuplicate 实现双层幂等）
            if (isDuplicate(event)) {
                log.warn("MQ 重复消息被拦截: topic={}, eventId={}", topic, eventId);
                return; // ACK
            }

            // 5. 执行业务逻辑
            processEvent(event);

            // 6. 记录消费成功（子类可覆盖 markConsumed 写入 DB + Redis）
            markConsumed(event);

            long duration = System.currentTimeMillis() - startTime;
            log.info("MQ 消费成功: topic={}, eventId={}, traceId={}, duration={}ms", topic, eventId, traceId, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("MQ 消费失败: topic={}, tag={}, msgId={}, traceId={}, duration={}ms", topic, tag, msgId, traceId, duration, e);
            // 抛异常触发 RocketMQ 重试（16 次后进入 DLQ）
            throw new RuntimeException("MQ 消费失败: " + topic, e);
        } finally {
            // 清理 MDC，防止线程池复用导致 traceId 串号
            TraceContext.clear();
        }
    }

    /**
     * 子类实现具体业务逻辑
     */
    protected abstract void processEvent(T event);

    /**
     * 获取事件类型的 Class（用于 JSON 反序列化）
     */
    protected abstract Class<T> getEventClass();

    /**
     * 幂等检查（默认不检查，子类覆盖实现 Redis + DB 双层幂等）
     *
     * @return true 表示已消费过（重复消息），false 表示首次消费
     */
    protected boolean isDuplicate(T event) {
        return false;
    }

    /**
     * 标记消费成功（子类覆盖实现写入 mq_consumed 表 + Redis 标记）
     */
    protected void markConsumed(T event) {
        // 默认空实现，子类按需覆盖
    }
}
