package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Outbox 补发任务：定时扫描 PENDING 记录，复用原始 eventId 与 payload 补发 C05。
 * <p>
 * 补发不检查设备当前 lifecycle_status；可恢复 MQ 失败持续保持 PENDING 并告警，
 * 仅不可恢复数据错误（如 payload 缺失）才标记 FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRetryTask {

    private final IotMqOutboxMapper outboxMapper;
    private final SosEventProducer sosEventProducer;
    private final Executor iotP0Executor;

    @Value("${iot.outbox.retry.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${iot.outbox.retry.fixed-delay-ms:10000}")
    public void retryPending() {
        LambdaQueryWrapper<IotMqOutbox> wrapper = new LambdaQueryWrapper<IotMqOutbox>()
                .eq(IotMqOutbox::getStatus, OutboxStatus.PENDING.getCode())
                .orderByAsc(IotMqOutbox::getCreatedAt)
                .last("LIMIT " + batchSize);

        List<IotMqOutbox> pending;
        try {
            pending = outboxMapper.selectList(wrapper);
        } catch (Exception e) {
            log.error("Outbox 补发扫描异常", e);
            return;
        }

        if (pending.isEmpty()) {
            return;
        }

        log.info("Outbox 补发扫描: {} 条 PENDING 记录", pending.size());
        for (IotMqOutbox outbox : pending) {
            // 不可恢复数据错误：payload 为空时直接标记 FAILED
            if (outbox.getPayload() == null) {
                log.error("Outbox payload 为空，标记 FAILED: eventId={}", outbox.getEventId());
                markFailed(outbox);
                continue;
            }

            iotP0Executor.execute(() -> {
                try {
                    TraceContext.setTraceId(extractTraceId(outbox));
                    sosEventProducer.send(outbox);
                } finally {
                    TraceContext.clear();
                }
            });
        }
    }

    private void markFailed(IotMqOutbox outbox) {
        try {
            outbox.setStatus(OutboxStatus.FAILED.getCode());
            outbox.setLastError("payload 为空，不可恢复");
            outboxMapper.updateById(outbox);
        } catch (Exception e) {
            log.error("标记 Outbox FAILED 失败: eventId={}", outbox.getEventId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTraceId(IotMqOutbox outbox) {
        Map<String, Object> payload = outbox.getPayload();
        if (payload != null && payload.get("traceId") instanceof String traceId) {
            return traceId;
        }
        return outbox.getEventId();
    }
}
