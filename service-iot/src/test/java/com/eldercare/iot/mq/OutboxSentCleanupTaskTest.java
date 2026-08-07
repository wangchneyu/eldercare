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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        ReflectionTestUtils.setField(task, "maxBatchesPerRun", 10);
    }

    @Test
    void cleanupExpiredSent_deletesSentinBatchesUntilNoneLeft() {
        when(outboxMapper.deleteSentOlderThan(any(), eq(2))).thenReturn(2, 2, 1);

        task.cleanupExpiredSent();

        verify(outboxMapper, times(3)).deleteSentOlderThan(any(), eq(2));
        verify(metrics, times(2)).sentOutboxDeleted(2L);
        verify(metrics).sentOutboxDeleted(1L);
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

    @Test
    void init_rejectsNonPositiveRetentionDays() {
        assertRejectedInit("sentRetentionDays", 0);
        assertRejectedInit("sentRetentionDays", -5);
    }

    @Test
    void init_rejectsInvalidBatchSize() {
        assertRejectedInit("batchSize", 0);
        assertRejectedInit("batchSize", 5001);
    }

    @Test
    void init_rejectsInvalidFixedDelay() {
        assertRejectedInit("fixedDelayMs", 59_999L);
    }

    @Test
    void init_rejectsInvalidMaxBatchesPerRun() {
        assertRejectedInit("maxBatchesPerRun", 0);
        assertRejectedInit("maxBatchesPerRun", 101);
    }

    @Test
    void cleanupExpiredSent_stopsAtMaxBatchesPerRun() {
        ReflectionTestUtils.setField(task, "maxBatchesPerRun", 3);
        when(outboxMapper.deleteSentOlderThan(any(), eq(2))).thenReturn(2);

        task.cleanupExpiredSent();

        verify(outboxMapper, times(3)).deleteSentOlderThan(any(), eq(2));
        verify(metrics, times(3)).sentOutboxDeleted(2L);
    }

    @Test
    void cleanupExpiredSent_partialSuccessThenFailureStillRecordsDeletedMetric() {
        when(outboxMapper.deleteSentOlderThan(any(), eq(2)))
                .thenReturn(2)
                .thenThrow(new RuntimeException("db connection lost"));

        task.cleanupExpiredSent();

        verify(outboxMapper, times(2)).deleteSentOlderThan(any(), eq(2));
        verify(metrics).sentOutboxDeleted(2L);
        verify(metrics, never()).sentOutboxDeleted(4L);
    }

    @Test
    void cleanupExpiredSent_metricFailureDoesNotAbortDeletion() {
        when(outboxMapper.deleteSentOlderThan(any(), eq(2))).thenReturn(2, 1);
        doThrow(new RuntimeException("metrics down")).when(metrics).sentOutboxDeleted(2L);

        task.cleanupExpiredSent();

        verify(outboxMapper, times(2)).deleteSentOlderThan(any(), eq(2));
        verify(metrics).sentOutboxDeleted(1L);
    }

    private void assertRejectedInit(String field, Object value) {
        ScheduledExecutorService badScheduler = mock(ScheduledExecutorService.class);
        OutboxSentCleanupTask bad = new OutboxSentCleanupTask(outboxMapper, metrics, badScheduler);
        ReflectionTestUtils.setField(bad, field, value);
        assertThrows(IllegalStateException.class, bad::init);
        verifyNoInteractions(badScheduler);
    }
}
