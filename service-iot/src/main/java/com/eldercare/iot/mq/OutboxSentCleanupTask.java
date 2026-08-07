package com.eldercare.iot.mq;

import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * C16-2 (frozen, 2026-08-07): low-priority cleanup of terminal P0 records.
 *
 * <p>Only {@code status = 'SENT'} records with {@code sent_at} older than the
 * configurable retention window (default 30 days) are deleted. PENDING, FAILED
 * and every other status are never removed by this task. Deletion runs in
 * bounded batches so each SQL statement stays short and no long transaction is
 * opened. The C04 failure-only table has its own independent terminal retention
 * policy and is not touched here.</p>
 */
@Slf4j
@Component
public class OutboxSentCleanupTask {

    private final IotMqOutboxMapper outboxMapper;
    private final IotMetrics metrics;
    private final ScheduledExecutorService scheduler;

    @Value("${iot.outbox.sent-retention-days:30}")
    private int sentRetentionDays = 30;

    @Value("${iot.outbox.sent-cleanup.batch-size:500}")
    private int batchSize = 500;

    @Value("${iot.outbox.sent-cleanup.fixed-delay-ms:3600000}")
    private long fixedDelayMs = 3_600_000L;

    public OutboxSentCleanupTask(IotMqOutboxMapper outboxMapper,
                                 IotMetrics metrics,
                                 @Qualifier("iotOutboxSentCleanupScheduler") ScheduledExecutorService scheduler) {
        this.outboxMapper = outboxMapper;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void init() {
        scheduler.scheduleWithFixedDelay(this::cleanupExpiredSent, fixedDelayMs, fixedDelayMs, TimeUnit.MILLISECONDS);
    }

    void cleanupExpiredSent() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(sentRetentionDays, ChronoUnit.DAYS);
        long totalDeleted = 0;
        try {
            int deleted;
            do {
                deleted = outboxMapper.deleteSentOlderThan(cutoff, batchSize);
                totalDeleted += deleted;
            } while (deleted == batchSize);
        } catch (Exception e) {
            log.warn("Outbox SENT cleanup failed after {} rows: reason={}", totalDeleted, e.getMessage());
            return;
        }
        if (totalDeleted > 0) {
            metrics.sentOutboxDeleted(totalDeleted);
            log.info("Outbox SENT cleanup removed: count={}, retentionDays={}, cutoff={}",
                    totalDeleted, sentRetentionDays, cutoff);
        }
    }
}