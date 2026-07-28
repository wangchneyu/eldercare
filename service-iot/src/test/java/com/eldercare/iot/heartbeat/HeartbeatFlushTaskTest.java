package com.eldercare.iot.heartbeat;

import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.parser.model.ParsedHeartbeat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatFlushTaskTest {

    @Mock
    IotDeviceInstanceMapper instanceMapper;
    @Mock
    ScheduledExecutorService scheduler;

    @Test
    void flushUpdatesOnlyDirtySnapshotsAndAcknowledgesThem() {
        HeartbeatManager manager = new HeartbeatManager();
        manager.recordHeartbeat(heartbeat("DEV-001"), 15);
        when(instanceMapper.updateHeartbeatSnapshots(anyList())).thenReturn(1);
        HeartbeatFlushTask task = new HeartbeatFlushTask(manager, instanceMapper, scheduler, 5, 100);

        assertEquals(1, task.flushPending());
        assertTrue(manager.snapshotDirtyStates().isEmpty());
        assertEquals(0, task.flushPending());
        verify(instanceMapper, times(1)).updateHeartbeatSnapshots(anyList());
    }

    @Test
    void failedDatabaseWriteStaysDirtyForNextScheduledRetry() {
        HeartbeatManager manager = new HeartbeatManager();
        manager.recordHeartbeat(heartbeat("DEV-001"), 15);
        when(instanceMapper.updateHeartbeatSnapshots(anyList()))
                .thenThrow(new IllegalStateException("db unavailable"));
        HeartbeatFlushTask task = new HeartbeatFlushTask(manager, instanceMapper, scheduler, 5, 100);

        assertEquals(0, task.flushPending());
        assertEquals(1, manager.snapshotDirtyStates().size());
    }

    @Test
    void fewerThanHundredDirtyDevicesDoesNotScheduleEarlyFlush() {
        HeartbeatManager manager = new HeartbeatManager();
        manager.recordHeartbeat(heartbeat("DEV-001"), 15);
        HeartbeatFlushTask task = new HeartbeatFlushTask(manager, instanceMapper, scheduler, 5, 100);

        task.requestFlushIfBatchReady();

        verifyNoInteractions(scheduler);
    }

    private ParsedHeartbeat heartbeat(String deviceId) {
        return new ParsedHeartbeat(
                "EVT-001", "MSG-001", deviceId, OffsetDateTime.now(), "trace", "P001", "RADAR");
    }
}
