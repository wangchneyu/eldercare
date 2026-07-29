package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotMqOutbox;
import com.eldercare.iot.enums.OutboxStatus;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Outbox 补发任务：原子 claim/租约机制防止首次发送与定时扫描、多个实例扫描重复并发发送。
 * <p>
 * 1. 每次扫描通过 {@code claimPendingRecords} 锁定最老未租约/已过期记录并设置租约；
 * 2. 仅处理本实例领取到的记录；
 * 3. 发送成功更新 SENT；失败延长租约，等待下次扫描；
 * 4. 崩溃后租约会过期，其他实例可回收。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRetryTask {

    private final IotMqOutboxMapper outboxMapper;
    private final SosEventProducer sosEventProducer;
    private final Executor iotP0Executor;
    private final IotMetrics metrics;

    @Value("${iot.outbox.retry.batch-size:100}")
    private int batchSize;

    @Value("${iot.outbox.retry.lease-minutes:5}")
    private int leaseMinutes;

    private String instanceId;

    @PostConstruct
    public void init() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        this.instanceId = host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${iot.outbox.retry.fixed-delay-ms:10000}")
    public void retryPending() {
        refreshMetrics();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseExpireAt = now.plus(leaseMinutes, ChronoUnit.MINUTES);

        List<IotMqOutbox> claimed;
        try {
            claimed = outboxMapper.claimPendingRecords(batchSize, now, leaseExpireAt, instanceId);
        } catch (Exception e) {
            log.error("Outbox 补发 claim 异常", e);
            return;
        }

        if (claimed.isEmpty()) {
            return;
        }

        log.info("Outbox 补发扫描: {} 条记录被实例 {} 领取", claimed.size(), instanceId);
        for (IotMqOutbox outbox : claimed) {
            // No persisted original JSON means a retry cannot safely reconstruct C05.
            if (outbox.getRawEnvelopeJson() == null || outbox.getRawEnvelopeJson().isBlank()) {
                log.error("Outbox rawEnvelopeJson 为空，标记 FAILED: eventId={}", outbox.getEventId());
                sosEventProducer.markUnrecoverable(outbox, "rawEnvelopeJson 为空，不可恢复");
                continue;
            }

            try {
                iotP0Executor.execute(() -> {
                    try {
                        TraceContext.setTraceId(extractTraceId(outbox));
                        sosEventProducer.send(outbox);
                    } catch (Exception e) {
                        log.error("Outbox 补发执行异常: eventId={}", outbox.getEventId(), e);
                        releaseLease(outbox);
                    } finally {
                        TraceContext.clear();
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("P0 executor is full; release Outbox lease: eventId={}", outbox.getEventId());
                releaseLease(outbox);
            }
        }
    }

    private void releaseLease(IotMqOutbox outbox) {
        try {
            int rows = outboxMapper.releaseLease(outbox.getEventId(), instanceId);
            if (rows == 0) {
                metrics.outboxLeaseConflict(outbox.getEventId());
            }
        } catch (Exception e) {
            log.error("释放 Outbox 租约失败: eventId={}", outbox.getEventId(), e);
        }
    }

    private void refreshMetrics() {
        try {
            metrics.updateOutboxSnapshot(outboxMapper.countPending(), outboxMapper.oldestPendingAgeSeconds());
        } catch (Exception e) {
            log.warn("Outbox metrics refresh failed: reason={}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTraceId(IotMqOutbox outbox) {
        Map<String, Object> rawEnvelope = outbox.getRawEnvelope();
        if (rawEnvelope != null && rawEnvelope.get("traceId") instanceof String traceId) {
            return traceId;
        }
        Map<String, Object> payload = outbox.getPayload();
        if (payload != null && payload.get("traceId") instanceof String traceId) {
            return traceId;
        }
        return outbox.getEventId();
    }
}
