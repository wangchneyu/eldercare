package com.eldercare.iot.heartbeat;

import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每 5 秒合并持久化心跳状态；累计到 100 台待刷设备时可由调用方提前触发。
 */
@Slf4j
@Component
public class HeartbeatFlushTask {

    private final HeartbeatManager heartbeatManager;
    private final IotDeviceInstanceMapper instanceMapper;
    private final ScheduledExecutorService scheduler;
    private final long flushIntervalSeconds;
    private final int batchTriggerSize;
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    public HeartbeatFlushTask(
            HeartbeatManager heartbeatManager,
            IotDeviceInstanceMapper instanceMapper,
            @Qualifier("iotHeartbeatFlushScheduler") ScheduledExecutorService scheduler,
            @Value("${iot.heartbeat.flush-interval-seconds:5}") long flushIntervalSeconds,
            @Value("${iot.heartbeat.batch-trigger-size:100}") int batchTriggerSize) {
        this.heartbeatManager = heartbeatManager;
        this.instanceMapper = instanceMapper;
        this.scheduler = scheduler;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.batchTriggerSize = batchTriggerSize;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(
                this::flushSafely, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    public void requestFlushIfBatchReady() {
        if (heartbeatManager.snapshotDirtyStates().size() < batchTriggerSize
                || !flushScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.execute(() -> {
            try {
                flushPending();
            } finally {
                flushScheduled.set(false);
            }
        });
    }

    void flushSafely() {
        try {
            flushPending();
        } catch (Exception e) {
            log.error("心跳状态合并写库失败", e);
        }
    }

    int flushPending() {
        List<HeartbeatState> snapshots = heartbeatManager.snapshotDirtyStates();
        if (snapshots.isEmpty()) {
            return 0;
        }
        try {
            int updated = instanceMapper.updateHeartbeatSnapshots(snapshots);
            snapshots.forEach(snapshot ->
                    heartbeatManager.acknowledgeFlushed(snapshot.deviceId(), snapshot.version()));
            log.debug("心跳状态批量写库完成: pending={}, updated={}", snapshots.size(), updated);
            return updated;
        } catch (Exception e) {
            log.error("心跳状态批量写库失败，将在下一轮重试: pending={}", snapshots.size(), e);
            return 0;
        }
    }
}
