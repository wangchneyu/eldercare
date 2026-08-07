package com.eldercare.iot.mq;

import com.eldercare.iot.entity.IotMqOutboxEscalation;
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
import java.util.ArrayList;
import java.util.List;
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
 * C16-3 unit coverage: config fail-fast, per-run batch cap, per-record audit and
 * metric resilience. Multi-instance / force idempotence and status semantics are
 * proven by {@code OutboxEscalationIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEscalationTaskTest {

    @Mock
    IotMqOutboxMapper outboxMapper;
    @Mock
    IotMetrics metrics;
    @Mock
    ScheduledExecutorService scheduler;

    private OutboxEscalationTask task;

    @BeforeEach
    void setUp() {
        task = new OutboxEscalationTask(outboxMapper, metrics, scheduler);
        task.init();
        ReflectionTestUtils.setField(task, "escalationWindowMinutes", 30);
        ReflectionTestUtils.setField(task, "batchSize", 2);
        ReflectionTestUtils.setField(task, "maxBatchesPerRun", 10);
    }

    @Test
    void scanOverduePending_usesWindowAsCreatedBeforeCutoff() {
        OffsetDateTime before = OffsetDateTime.now().minusMinutes(30);
        when(outboxMapper.escalatePendingOlderThan(any(), eq(2), any(), eq(OutboxEscalationTask.ESCALATION_REASON)))
                .thenReturn(escalations(1));

        task.scanOverduePending();

        OffsetDateTime after = OffsetDateTime.now().minusMinutes(30);
        org.mockito.ArgumentCaptor<OffsetDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(outboxMapper).escalatePendingOlderThan(captor.capture(), anyInt(), any(), eq(OutboxEscalationTask.ESCALATION_REASON));
        OffsetDateTime cutoff = captor.getValue();
        assertTrue(!cutoff.isBefore(before) && !cutoff.isAfter(after));
        verify(metrics).outboxEscalated(1L);
    }

    @Test
    void scanOverdue_noCandidatesDoesNotEmitMetric() {
        when(outboxMapper.escalatePendingOlderThan(any(), anyInt(), any(), any()))
                .thenReturn(escalations(0));

        task.scanOverduePending();

        verify(outboxMapper).escalatePendingOlderThan(any(), anyInt(), any(), any());
        verify(metrics, never()).outboxEscalated(anyLong());
    }

    @Test
    void scanOverdue_stopsAtMaxBatchesPerRun() {
        ReflectionTestUtils.setField(task, "maxBatchesPerRun", 3);
        when(outboxMapper.escalatePendingOlderThan(any(), eq(2), any(), any())).thenReturn(escalations(2));

        task.scanOverduePending();

        verify(outboxMapper, times(3)).escalatePendingOlderThan(any(), eq(2), any(), any());
        verify(metrics, times(3)).outboxEscalated(2L);
    }

    @Test
    void scanOverdue_partialBatchThenFailureStillRecordsEscalatedMetric() {
        when(outboxMapper.escalatePendingOlderThan(any(), eq(2), any(), any()))
                .thenReturn(escalations(2))
                .thenThrow(new RuntimeException("db connection lost"));

        task.scanOverduePending();

        verify(outboxMapper, times(2)).escalatePendingOlderThan(any(), eq(2), any(), any());
        verify(metrics).outboxEscalated(2L);
        verify(metrics, never()).outboxEscalated(4L);
    }

    @Test
    void scanOverdue_metricFailureDoesNotAbortScan() {
        when(outboxMapper.escalatePendingOlderThan(any(), eq(2), any(), any()))
                .thenReturn(escalations(2), escalations(1));
        doThrow(new RuntimeException("metrics down")).when(metrics).outboxEscalated(2L);

        task.scanOverduePending();

        verify(outboxMapper, times(2)).escalatePendingOlderThan(any(), eq(2), any(), any());
        verify(metrics).outboxEscalated(1L);
    }

    @Test
    void init_rejectsInvalidEscalationWindow() {
        assertRejectedInit("escalationWindowMinutes", 0);
        assertRejectedInit("escalationWindowMinutes", -5);
    }

    @Test
    void init_rejectsInvalidBatchSize() {
        assertRejectedInit("batchSize", 0);
        assertRejectedInit("batchSize", 5001);
    }

    @Test
    void init_rejectsInvalidMaxBatchesPerRun() {
        assertRejectedInit("maxBatchesPerRun", 0);
        assertRejectedInit("maxBatchesPerRun", 101);
    }

    @Test
    void init_rejectsInvalidFixedDelayInterval() {
        assertRejectedInit("fixedDelayMs", 59_999L);
    }

    private void assertRejectedInit(String field, Object value) {
        ScheduledExecutorService badScheduler = mock(ScheduledExecutorService.class);
        OutboxEscalationTask bad = new OutboxEscalationTask(outboxMapper, metrics, badScheduler);
        ReflectionTestUtils.setField(bad, field, value);
        assertThrows(IllegalStateException.class, bad::init);
        verifyNoInteractions(badScheduler);
    }

    private List<IotMqOutboxEscalation> escalations(int size) {
        List<IotMqOutboxEscalation> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            IotMqOutboxEscalation escape = new IotMqOutboxEscalation();
            escape.setId((long) (i + 1));
            escape.setEventId("EVT-" + i);
            escape.setDeviceId("DEV-" + i);
            escape.setEventType("SOS_TRIGGERED");
            escape.setCreatedAt(OffsetDateTime.now().minus(31, ChronoUnit.MINUTES));
            escape.setEscalatedAt(OffsetDateTime.now());
            escape.setRetryCount(3);
            escape.setLastError("mq timeout");
            escape.setTraceId("trace-" + i);
            list.add(escape);
        }
        return list;
    }
}