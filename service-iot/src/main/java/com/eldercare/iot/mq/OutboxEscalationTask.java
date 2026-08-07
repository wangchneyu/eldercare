package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotMqOutboxEscalation;
import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * C16-3 (frozen, 2026-08-07): one-shot P0 delivery-window escalation, retry-safe.
 *
 * <p>Recoverable MQ infrastructure failures must remain {@code PENDING} and keep
 * being retried by {@link OutboxRetryTask}. Crossing {@code created_at +
 * escalationWindow} (default 30 minutes) atomically records {@code escalated_at}
 * at most once per record and emits logs/Prometheus metrics - the record stays
 * {@code PENDING}, keeps its frozen {@code rawEnvelopeJson}/{@code eventId}/
 * {@code traceId}, and is still claimed by the retry task. No time-based terminal
 * state exists: no {@code ABANDONED}, no stop-retrying, no deletion of the types.</p>
 *
 * <p>Escalation is low-priority and isolated on its own daemon scheduler. Each run
 * executes at most {@code maxBatchesPerRun} batches of {@code batchSize}, mirroring
 * {@link OutboxSentCleanupTask}; configuration is validated fail-fast in
 * {@link #init()} so an illegal value prevents scheduling entirely. The atomic
 * {@code escalated_at IS NULL} UPDATE makes multiple instances safe.</p>
 */
@Slf4j
@Component
public class OutboxEscalationTask {

    static final String ESCALATION_REASON = "DELIVERY_WINDOW_EXCEEDED";

    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 5000;
    private static final long MIN_FIXED_DELAY_MS = 60_000L;
    private static final int MIN_MAX_BATCHES_PER_RUN = 1;
    private static final int MAX_MAX_BATCHES_PER_RUN = 100;

    private final IotMqOutboxMapper outboxMapper;
    private final IotMetrics metrics;
    private final ScheduledExecutorService scheduler;

    @Value("${iot.outbox.escalation.window-minutes:30}")
    private int escalationWindowMinutes = 30;

    @Value("${iot.outbox.escalation.batch-size:100}")
    private int batchSize = 100;

    @Value("${iot.outbox.escalation.max-batches-per-run:10}")
    private int maxBatchesPerRun = 10;

    @Value("${iot.outbox.escalation.fixed-delay-ms:60000}")
    private long fixedDelayMs = 60_000L;

    public OutboxEscalationTask(IotMqOutboxMapper outboxMapper,
                                IotMetrics metrics,
                                @Qualifier("iotOutboxEscalationScheduler") ScheduledExecutorService scheduler) {
        this.outboxMapper = outboxMapper;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void init() {
        validateConfig();
        scheduler.scheduleWithFixedDelay(this::scanOverduePending, fixedDelayMs, fixedDelayMs, TimeUnit.MILLISECONDS);
    }

    private void validateConfig() {
        if (escalationWindowMinutes < 1) {
            throw new IllegalStateException(
                    "iot.outbox.escalation.window-minutes must be >= 1, got " + escalationWindowMinutes);
        }
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "iot.outbox.escalation.batch-size must be in 1.." + MAX_BATCH_SIZE + ", got " + batchSize);
        }
        if (maxBatchesPerRun < MIN_MAX_BATCHES_PER_RUN || maxBatchesPerRun > MAX_MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException(
                    "iot.outbox.escalation.max-batches-per-run must be in 1.." + MAX_MAX_BATCHES_PER_RUN
                            + ", got " + maxBatchesPerRun);
        }
        if (fixedDelayMs < MIN_FIXED_DELAY_MS) {
            throw new IllegalStateException(
                    "iot.outbox.escalation.fixed-delay-ms must be >= " + MIN_FIXED_DELAY_MS + ", got " + fixedDelayMs);
        }
    }

    void scanOverduePending() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minus(escalationWindowMinutes, ChronoUnit.MINUTES);
        int batchesRun = 0;
        long totalEscalated = 0;
        boolean backlogRemains = false;
        try {
            List<IotMqOutboxEscalation> batch;
            do {
                if (batchesRun >= maxBatchesPerRun) {
                    break;
                }
                batch = outboxMapper.escalatePendingOlderThan(cutoff, batchSize, now, ESCALATION_REASON);
                batchesRun++;
                if (!batch.isEmpty()) {
                    totalEscalated += batch.size();
                    emitEscalationAudit(batch, now);
                }
                backlogRemains = batch.size() == batchSize;
            } while (backlogRemains);
        } catch (Exception e) {
            log.error("Outbox delivery-window escalation failed after {} records escalated in {} batches",
                    totalEscalated, batchesRun, e);
            return;
        }
        if (backlogRemains && batchesRun >= maxBatchesPerRun) {
            log.warn("Outbox escalation reached per-run batch limit; backlog remains for the next run: "
                            + "escalatedThisRun={}, batchesRun={}, maxBatchesPerRun={}, escalationWindowMinutes={}, cutoff={}",
                    totalEscalated, batchesRun, maxBatchesPerRun, escalationWindowMinutes, cutoff);
        } else if (totalEscalated > 0) {
            log.info("Outbox escalation scan completed: escalated={}, batchesRun={}, escalationWindowMinutes={}, cutoff={}",
                    totalEscalated, batchesRun, escalationWindowMinutes, cutoff);
        }
    }

    /**
     * Structured WARN per escalated record for operations. Only audit fields are
     * logged; the full {@code rawEnvelopeJson} is never printed. The metric is
     * incremented per batch after the DB UPDATE committed; a metric failure is
     * logged and must never affect escalation state or trigger deletion.
     */
    private void emitEscalationAudit(List<IotMqOutboxEscalation> batch, OffsetDateTime escalatedAt) {
        for (IotMqOutboxEscalation record : batch) {
            long pendingAgeSeconds = record.getCreatedAt() == null ? -1L
                    : Duration.between(record.getCreatedAt(), escalatedAt).getSeconds();
            log.warn("P0 delivery overdue beyond window; retry continues (PENDING): "
                            + "eventId={}, deviceId={}, eventType={}, createdAt={}, escalatedAt={}, "
                            + "pendingAgeSeconds={}, retryCount={}, lastError={}, traceId={}, reason={}",
                    record.getEventId(), record.getDeviceId(), record.getEventType(), record.getCreatedAt(),
                    record.getEscalatedAt(), pendingAgeSeconds, record.getRetryCount(), record.getLastError(),
                    record.getTraceId(), ESCALATION_REASON);
        }
        try {
            metrics.outboxEscalated(batch.size());
        } catch (Exception metricFailure) {
            log.warn("Outbox escalation metric update failed; DB state already committed, scan continues: "
                    + "count={}, reason={}", batch.size(), metricFailure.getMessage());
        }
    }
}