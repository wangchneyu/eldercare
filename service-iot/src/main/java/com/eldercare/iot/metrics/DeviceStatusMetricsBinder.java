package com.eldercare.iot.metrics;

import com.eldercare.iot.enums.OnlineStatus;
import com.eldercare.iot.heartbeat.HeartbeatManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Binds in-memory heartbeat state to cheap, scrape-safe gauges. */
@Component
@RequiredArgsConstructor
public class DeviceStatusMetricsBinder {

    private final IotMetrics metrics;
    private final HeartbeatManager heartbeatManager;

    @PostConstruct
    public void bind() {
        metrics.bindDeviceStatusGauges(
                () -> heartbeatManager.statusCount(OnlineStatus.ONLINE),
                () -> heartbeatManager.statusCount(OnlineStatus.OFFLINE),
                heartbeatManager::oldestOnlineHeartbeatAgeSeconds
        );
    }
}
