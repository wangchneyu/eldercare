package com.eldercare.iot.heartbeat;

import com.eldercare.iot.enums.OnlineStatus;
import com.eldercare.iot.parser.model.ParsedEvent;
import com.eldercare.iot.parser.model.ParsedHeartbeat;
import com.eldercare.iot.parser.model.ParsedSosEvent;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备心跳内存状态机。仅由阶段五完成合法设备、生命周期和协议校验后的消息驱动。
 */
@Slf4j
@Component
public class HeartbeatManager {

    static final int DEFAULT_TIMEOUT_SECONDS = 15;

    private final ConcurrentHashMap<String, HeartbeatState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HeartbeatState> dirtyStates = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<DeviceStatusEvent> statusEvents = new ConcurrentLinkedQueue<>();
    private static final int MAX_STATUS_EVENTS = 10_000;

    private final AtomicLong versionSequence = new AtomicLong();
    private final Clock clock;

    public HeartbeatManager() {
        this(Clock.systemUTC());
    }

    HeartbeatManager(Clock clock) {
        this.clock = clock;
    }

    public Optional<DeviceStatusEvent> recordHeartbeat(ParsedEvent heartbeat, Integer timeoutSeconds) {
        OffsetDateTime receivedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        int effectiveTimeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : timeoutSeconds;
        DeviceStatusEvent[] transition = new DeviceStatusEvent[1];

        states.compute(heartbeat.deviceId(), (deviceId, current) -> {
            OnlineStatus oldStatus = current == null ? OnlineStatus.UNKNOWN : current.onlineStatus();
            OffsetDateTime effectiveHeartbeatAt = current != null
                    && current.lastHeartbeatAt() != null
                    && current.lastHeartbeatAt().isAfter(receivedAt)
                    ? current.lastHeartbeatAt()
                    : receivedAt;
            if (oldStatus != OnlineStatus.ONLINE) {
                String eventType = oldStatus == OnlineStatus.OFFLINE ? "RECOVERED" : "ONLINE";
                transition[0] = new DeviceStatusEvent(
                        deviceId,
                        deviceTypeOf(heartbeat),
                        oldStatus,
                        OnlineStatus.ONLINE,
                        eventType,
                        receivedAt,
                        heartbeat.traceId());
            }
            HeartbeatState updated = new HeartbeatState(
                    deviceId,
                    deviceTypeOf(heartbeat),
                    effectiveHeartbeatAt,
                    OnlineStatus.ONLINE,
                    effectiveTimeout,
                    heartbeat.traceId(),
                    versionSequence.incrementAndGet());
            dirtyStates.put(deviceId, updated);
            return updated;
        });

        publish(transition[0]);
        return Optional.ofNullable(transition[0]);
    }

    public List<DeviceStatusEvent> detectTimeouts() {
        return detectTimeouts(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    }

    List<DeviceStatusEvent> detectTimeouts(OffsetDateTime now) {
        List<DeviceStatusEvent> transitions = new ArrayList<>();
        states.forEach((deviceId, ignored) -> states.computeIfPresent(deviceId, (key, current) -> {
            if (current.onlineStatus() != OnlineStatus.ONLINE || current.lastHeartbeatAt() == null) {
                return current;
            }
            if (!now.isAfter(current.lastHeartbeatAt().plusSeconds(current.timeoutSeconds()))) {
                return current;
            }

            DeviceStatusEvent event = new DeviceStatusEvent(
                    current.deviceId(),
                    current.deviceType(),
                    OnlineStatus.ONLINE,
                    OnlineStatus.OFFLINE,
                    "OFFLINE",
                    now,
                    current.traceId());
            transitions.add(event);
            publish(event);
            HeartbeatState updated = new HeartbeatState(
                    current.deviceId(),
                    current.deviceType(),
                    current.lastHeartbeatAt(),
                    OnlineStatus.OFFLINE,
                    current.timeoutSeconds(),
                    current.traceId(),
                    versionSequence.incrementAndGet());
            dirtyStates.put(deviceId, updated);
            return updated;
        }));
        return transitions;
    }

    public Optional<HeartbeatState> getState(String deviceId) {
        return Optional.ofNullable(states.get(deviceId));
    }

    public List<HeartbeatState> snapshotDirtyStates() {
        return List.copyOf(dirtyStates.values());
    }

    public boolean acknowledgeFlushed(String deviceId, long version) {
        return dirtyStates.computeIfPresent(deviceId, (key, current) ->
                current.version() == version ? null : current) == null;
    }

    public List<DeviceStatusEvent> recentEvents(String deviceId) {
        return statusEvents.stream()
                .filter(event -> event.deviceId().equals(deviceId))
                .toList();
    }

    private String deviceTypeOf(ParsedEvent event) {
        if (event instanceof ParsedHeartbeat heartbeat) {
            return heartbeat.deviceType();
        }
        if (event instanceof ParsedVitalSign vitalSign) {
            return vitalSign.deviceType();
        }
        if (event instanceof ParsedSosEvent sosEvent) {
            return sosEvent.deviceType();
        }
        return null;
    }

    private void publish(DeviceStatusEvent event) {
        if (event == null) {
            return;
        }
        statusEvents.add(event);
        while (statusEvents.size() > MAX_STATUS_EVENTS) {
            statusEvents.poll();
        }
        log.info("设备心跳状态变更: deviceId={}, deviceType={}, oldStatus={}, newStatus={}, eventType={}, traceId={}",
                event.deviceId(), event.deviceType(), event.oldStatus(), event.newStatus(), event.eventType(), event.traceId());
    }
}
