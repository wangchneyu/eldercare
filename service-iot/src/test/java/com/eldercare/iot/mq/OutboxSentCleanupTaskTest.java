package com.eldercare.iot.mq;

import com.eldercare.iot.mapper.IotMqOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C16-2 (frozen, 2026-08-07): unit coverage for the low-priority P0 SENT cleanup
 * task. The task only ever calls {@code deleteSentOlderThan}; the status
 * restriction to SENT is enforced by the SQL boundary tests.
 */
@ExtendWith(MockitoExtension.class)
class OutboxSentCleanupTaskTest {

    @Mock
    IotMqOutboxMapper outboxMapper;
    @Mock
    IotMetrics metrics;
    @Mock
    ScheduledExecutorService scheduler;

    private OutboxSentCleanupTask task;

    @BeforeEach
    void setUp() {
        task = new OutboxSentCleanupTask(outboxMapper, metrics, scheduler);
        task.init();
        ReflectionTestUtils.setField(task, "sentRetentionDays", 30);
        ReflectionTestUtils.setField(task, "batchSize", 2);
    }

    @Test
    void cleanupExpiredSent_deletesSentinBatchesUntilNoneLeft() {
        when(outboxMapper.deleteSentOlderThan(any(), eq(2))).thenReturn(2, 2, 1);

        task.cleanupExpiredSent();

        verify(outboxMapper, times(3)).deleteSentOlderThan(any(), eq(2));
        verify(metrics).sentOutboxDeleted(5L);
    }

    @Test
    void cleanupExpiredSent_usesRetentionWindowAsCutoff() {
        OffsetDateTime before = OffsetDateTime.now().minusDays(30);
        when(outboxMapper.deleteSentOlderThan(any(), eq(2))).thenReturn(0);

        task.cleanupExpiredSent();

        OffsetDateTime after = OffsetDateTime.now().minusDays(30);
        org.mockito.ArgumentCaptor<OffsetDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(outboxMapper).deleteSentOlderThan(captor.capture(), anyInt());
        OffsetDateTime cutoff = captor.getValue();
        assertTrue(!cutoff.isBefore(before) && !cutoff.isAfter(after));
    }

    @Test
    void cleanupExpiredSent_noRecordsDoesNotEmitMetric() {
        when(outboxMapper.deleteSentOlderThan(any(), eq(2))).thenReturn(0);

        task.cleanupExpiredSent();

        verify(outboxMapper).deleteSentOlderThan(any(), eq(2));
        verify(metrics, never()).sentOutboxDeleted(anyLong());
    }

    @Test
    void cleanupExpiredSent_retentionIsConfigurable() {
        ReflectionTestUtils.setField(task, "sentRetentionDays", 7);
        OffsetDateTime before = OffsetDateTime.now().minusDays(7);
        when(outboxMapper.deleteSentOlderThan(any(), eq(2))).thenReturn(0);

        task.cleanupExpiredSent();

        OffsetDateTime after = OffsetDateTime.now().minusDays(7);
        org.mockito.ArgumentCaptor<OffsetDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(outboxMapper).deleteSentOlderThan(captor.capture(), anyInt());
        OffsetDateTime cutoff = captor.getValue();
        assertTrue(!cutoff.isBefore(before) && !cutoff.isAfter(after));
    }
}