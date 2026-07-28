package com.eldercare.iot.heartbeat;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 每秒扫描在线设备并执行超时状态转换。调度器由 Spring 管理并在应用关闭时停止。
 */
@Slf4j
@Component
public class HeartbeatTimeoutScanner {

    private final HeartbeatManager heartbeatManager;
    private final ScheduledExecutorService scheduler;

    public HeartbeatTimeoutScanner(
            HeartbeatManager heartbeatManager,
            @Qualifier("iotHeartbeatScanScheduler") ScheduledExecutorService scheduler) {
        this.heartbeatManager = heartbeatManager;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::scanSafely, 1, 1, TimeUnit.SECONDS);
    }

    void scanSafely() {
        try {
            heartbeatManager.detectTimeouts();
        } catch (Exception e) {
            log.error("心跳超时扫描失败", e);
        }
    }
}
