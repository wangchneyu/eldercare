package com.eldercare.edge.runtime;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.forward.CloudForwardTask;
import com.eldercare.edge.metrics.EdgeMetricsRefreshTask;
import com.eldercare.edge.mqtt.ManagedMqttClient;
import com.eldercare.edge.notify.NotificationRetryTask;
import com.eldercare.edge.storage.EdgeCleanupTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 应用运行期编排：连接本地/云端 MQTT，并在专用调度线程上启动
 * 补传（P0 优先 FIFO）、通知重投与指标刷新循环。
 */
@Component
public class EdgeRuntimeLifecycle implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EdgeRuntimeLifecycle.class);

    private final EdgeProperties properties;
    private final ManagedMqttClient localClient;
    private final ManagedMqttClient cloudClient;
    private final CloudForwardTask forwardTask;
    private final NotificationRetryTask notificationRetryTask;
    private final EdgeMetricsRefreshTask metricsRefreshTask;
    private final EdgeCleanupTask cleanupTask;
    private final ScheduledExecutorService forwardScheduler;
    private final ScheduledExecutorService notificationScheduler;
    private final ScheduledExecutorService metricsScheduler;

    public EdgeRuntimeLifecycle(EdgeProperties properties,
                                @Qualifier("edgeLocalMqttClient") ManagedMqttClient localClient,
                                @Qualifier("edgeCloudMqttClient") ManagedMqttClient cloudClient,
                                CloudForwardTask forwardTask,
                                NotificationRetryTask notificationRetryTask,
                                EdgeMetricsRefreshTask metricsRefreshTask,
                                EdgeCleanupTask cleanupTask,
                                ScheduledExecutorService edgeForwardScheduler,
                                ScheduledExecutorService edgeNotificationScheduler,
                                ScheduledExecutorService edgeMetricsScheduler) {
        this.properties = properties;
        this.localClient = localClient;
        this.cloudClient = cloudClient;
        this.forwardTask = forwardTask;
        this.notificationRetryTask = notificationRetryTask;
        this.metricsRefreshTask = metricsRefreshTask;
        this.cleanupTask = cleanupTask;
        this.forwardScheduler = edgeForwardScheduler;
        this.notificationScheduler = edgeNotificationScheduler;
        this.metricsScheduler = edgeMetricsScheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        localClient.connect();
        cloudClient.connect();

        forwardScheduler.scheduleWithFixedDelay(() -> runQuietly(forwardTask::runOnce),
                1L, properties.getForward().getIntervalMs(), TimeUnit.MILLISECONDS);
        notificationScheduler.scheduleWithFixedDelay(() -> runQuietly(notificationRetryTask::runOnce),
                1L, properties.getNotification().getIntervalMs(), TimeUnit.MILLISECONDS);
        metricsScheduler.scheduleWithFixedDelay(() -> runQuietly(metricsRefreshTask::runOnce),
                1L, 5_000L, TimeUnit.MILLISECONDS);
        metricsScheduler.scheduleWithFixedDelay(() -> runQuietly(cleanupTask::runOnce),
                60L, properties.getSqlite().getCleanupIntervalMs(), TimeUnit.MILLISECONDS);

        log.info("edge_relay_runtime_ready siteId={} localBroker={} cloudBroker={} terminals={}",
                properties.getSiteId(), properties.getLocalMqtt().getBrokerUrl(),
                properties.getCloudMqtt().getBrokerUrl(), properties.getCaregiverTerminalIds());
    }

    private void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("edge_runtime_task_failed error={}", e.getMessage());
        }
    }
}