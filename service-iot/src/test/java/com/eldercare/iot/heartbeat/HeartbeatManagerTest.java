package com.eldercare.iot.heartbeat;

import com.eldercare.iot.enums.OnlineStatus;
import com.eldercare.iot.parser.model.ParsedHeartbeat;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatManagerTest {

    private static final Instant START = Instant.parse("2026-07-28T02:00:00Z");

    @Test
    void firstHeartbeatProducesOnlineOnlyOnce() {
        HeartbeatManager manager = new HeartbeatManager(Clock.fixed(START, ZoneOffset.UTC));

        DeviceStatusEvent first = manager.recordHeartbeat(heartbeat("trace-1"), 15).orElseThrow();
        assertEquals(OnlineStatus.UNKNOWN, first.oldStatus());
        assertEquals(OnlineStatus.ONLINE, first.newStatus());
        assertEquals("ONLINE", first.eventType());

        assertTrue(manager.recordHeartbeat(heartbeat("trace-2"), 15).isEmpty());
        assertEquals(1, manager.recentEvents("DEV-001").size());
        assertEquals(OnlineStatus.ONLINE, manager.getState("DEV-001").orElseThrow().onlineStatus());
    }

    @Test
    void timeoutIsDebouncedAndRecoveryProducesRecoveredOnce() {
        HeartbeatManager manager = new HeartbeatManager(Clock.fixed(START, ZoneOffset.UTC));
        manager.recordHeartbeat(heartbeat("trace-online"), 15);

        assertTrue(manager.detectTimeouts(OffsetDateTime.ofInstant(START.plusSeconds(15), ZoneOffset.UTC)).isEmpty());
        List<DeviceStatusEvent> offline = manager.detectTimeouts(
                OffsetDateTime.ofInstant(START.plusSeconds(16), ZoneOffset.UTC));
        assertEquals(1, offline.size());
        assertEquals("OFFLINE", offline.get(0).eventType());
        assertTrue(manager.detectTimeouts(OffsetDateTime.ofInstant(START.plusSeconds(60), ZoneOffset.UTC)).isEmpty());

        DeviceStatusEvent recovered = manager.recordHeartbeat(heartbeat("trace-recovered"), 15).orElseThrow();
        assertEquals("RECOVERED", recovered.eventType());
        assertEquals(OnlineStatus.OFFLINE, recovered.oldStatus());
        assertEquals(OnlineStatus.ONLINE, recovered.newStatus());
        assertTrue(manager.recordHeartbeat(heartbeat("trace-still-online"), 15).isEmpty());
        assertEquals(3, manager.recentEvents("DEV-001").size());
    }

    @Test
    void dirtySnapshotKeepsNewerHeartbeatWhenOlderFlushCompletes() {
        HeartbeatManager manager = new HeartbeatManager(Clock.fixed(START, ZoneOffset.UTC));
        manager.recordHeartbeat(heartbeat("trace-1"), null);
        HeartbeatState first = manager.snapshotDirtyStates().get(0);

        manager.recordHeartbeat(heartbeat("trace-2"), null);
        HeartbeatState second = manager.snapshotDirtyStates().get(0);
        assertTrue(second.version() > first.version());
        assertFalse(manager.acknowledgeFlushed(first.deviceId(), first.version()));
        assertEquals(second.version(), manager.snapshotDirtyStates().get(0).version());

        assertTrue(manager.acknowledgeFlushed(second.deviceId(), second.version()));
        assertTrue(manager.snapshotDirtyStates().isEmpty());
        assertEquals(HeartbeatManager.DEFAULT_TIMEOUT_SECONDS,
                manager.getState("DEV-001").orElseThrow().timeoutSeconds());
    }

    @Test
    void statusCountsAndOldestHeartbeatAgeReflectCurrentState() {
        HeartbeatManager manager = new HeartbeatManager(Clock.fixed(START, ZoneOffset.UTC));
        manager.recordHeartbeat(heartbeat("trace-1"), 15);

        assertEquals(1, manager.statusCount(OnlineStatus.ONLINE));
        assertEquals(0, manager.statusCount(OnlineStatus.OFFLINE));
        assertEquals(0, manager.oldestOnlineHeartbeatAgeSeconds());

        manager.detectTimeouts(OffsetDateTime.ofInstant(START.plusSeconds(16), ZoneOffset.UTC));
        assertEquals(0, manager.statusCount(OnlineStatus.ONLINE));
        assertEquals(1, manager.statusCount(OnlineStatus.OFFLINE));
    }

    @Test
    void publishesOnlyDebouncedTransitionsToTheC09DispatchBoundary() {
        List<DeviceStatusEvent> published = new ArrayList<>();
        HeartbeatManager manager = new HeartbeatManager(Clock.fixed(START, ZoneOffset.UTC), published::add);

        manager.recordHeartbeat(heartbeat("trace-online"), 15);
        manager.recordHeartbeat(heartbeat("trace-still-online"), 15);
        manager.detectTimeouts(OffsetDateTime.ofInstant(START.plusSeconds(16), ZoneOffset.UTC));
        manager.detectTimeouts(OffsetDateTime.ofInstant(START.plusSeconds(60), ZoneOffset.UTC));
        manager.recordHeartbeat(heartbeat("trace-recovered"), 15);

        assertEquals(3, published.size());
        assertEquals(List.of("ONLINE", "OFFLINE", "RECOVERED"),
                published.stream().map(DeviceStatusEvent::eventType).toList());
        assertEquals("trace-recovered", published.get(2).traceId());
    }

    private ParsedHeartbeat heartbeat(String traceId) {
        return new ParsedHeartbeat(
                "EVT-001",
                "MSG-001",
                "DEV-001",
                OffsetDateTime.ofInstant(START, ZoneOffset.UTC),
                traceId,
                "P001",
                "RADAR");
    }
}
