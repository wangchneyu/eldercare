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
 * opened. Each run executes at most {@code maxBatchesPerRun} batches (default
 * 10), so a single run deletes at most {@code batchSize * maxBatchesPerRun}
 * rows (default 5000/h); a still-full last batch logs a backlog signal for the
 * next run instead of draining an unbounded backlog. The C04 failure-only
 * table has its own independent terminal retention policy and is not touched
 * here.</p>
 *
 * <p>Configuration is validated fail-fast in {@link #init()}: an illegal value
 * prevents scheduling entirely and nothing is ever deleted.</p>
 */
@Slf4j
@Component
public class OutboxSentCleanupTask {

    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 5000;
    private static final long MIN_FIXED_DELAY_MS = 60_000L;
    private static final int MIN_MAX_BATCHES_PER_RUN = 1;
    private static final int MAX_MAX_BATCHES_PER_RUN = 100;

    private final IotMqOutboxMapper outboxMapper;
    private final IotMetrics metrics;
    private final ScheduledExecutorService scheduler;

    @Value("${iot.outbox.sent-retention-days:30}")
    private int sentRetentionDays = 30;

    @Value("${iot.outbox.sent-cleanup.batch-size:500}")
    private int batchSize = 500;

    @Value("${iot.outbox.sent-cleanup.fixed-delay-ms:3600000}")
    private long fixedDelayMs = 3_600_000L;

    @Value("${iot.outbox.sent-cleanup.max-batches-per-run:10}")
    private int maxBatchesPerRun = 10;

    public OutboxSentCleanupTask(IotMqOutboxMapper outboxMapper,
                                 IotMetrics metrics,
                                 @Qualifier("iotOutboxSentCleanupScheduler") ScheduledExecutorService scheduler) {
        this.outboxMapper = outboxMapper;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void init() {
        validateConfig();
        scheduler.scheduleWithFixedDelay(this::cleanupExpiredSent, fixedDelayMs, fixedDelayMs, TimeUnit.MILLISECONDS);
    }

    private void validateConfig() {
        if (sentRetentionDays < 1) {
            throw new IllegalStateException(
                    "iot.outbox.sent-retention-days must be >= 1, got " + sentRetentionDays);
        }
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "iot.outbox.sent-cleanup.batch-size must be in 1.." + MAX_BATCH_SIZE + ", got " + batchSize);
        }
        if (fixedDelayMs < MIN_FIXED_DELAY_MS) {
            throw new IllegalStateException(
                    "iot.outbox.sent-cleanup.fixed-delay-ms must be >= " + MIN_FIXED_DELAY_MS + ", got " + fixedDelayMs);
        }
        if (maxBatchesPerRun < MIN_MAX_BATCHES_PER_RUN || maxBatchesPerRun > MAX_MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException(
                    "iot.outbox.sent-cleanup.max-batches-per-run must be in 1.." + MAX_MAX_BATCHES_PER_RUN
                            + ", got " + maxBatchesPerRun);
        }
    }

    void cleanupExpiredSent() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(sentRetentionDays, ChronoUnit.DAYS);
        long totalDeleted = 0;
        int batchesRun = 0;
        boolean backlogRemains = false;
        try {
            int deleted;
            do {
                if (batchesRun >= maxBatchesPerRun) {
                    break;
                }
                deleted = outboxMapper.deleteSentOlderThan(cutoff, batchSize);
                batchesRun++;
                if (deleted > 0) {
                    totalDeleted += deleted;
                    try {
                        metrics.sentOutboxDeleted(deleted);
                    } catch (Exception metricFailure) {
                        log.warn("Outbox SENT cleanup metric update failed; rows already deleted, run continues: "
                                + "count={}, reason={}", deleted, metricFailure.getMessage());
                    }
                }
                backlogRemains = deleted == batchSize;
            } while (backlogRemains);
        } catch (Exception e) {
            log.error("Outbox SENT cleanup execution failed after {} rows deleted in {} batches",
                    totalDeleted, batchesRun, e);
            return;
        }
        if (backlogRemains && batchesRun >= maxBatchesPerRun) {
            log.warn("Outbox SENT cleanup reached per-run batch limit; backlog remains for the next run: "
                            + "deletedThisRun={}, batchesRun={}, maxBatchesPerRun={}, retentionDays={}, cutoff={}",
                    totalDeleted, batchesRun, maxBatchesPerRun, sentRetentionDays, cutoff);
        } else if (totalDeleted > 0) {
            log.info("Outbox SENT cleanup completed: deleted={}, batchesRun={}, retentionDays={}, cutoff={}",
                    totalDeleted, batchesRun, sentRetentionDays, cutoff);
        }
    }
}
