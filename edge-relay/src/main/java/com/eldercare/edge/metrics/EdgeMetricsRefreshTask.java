package com.eldercare.edge.metrics;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.enums.IngressStatus;
import com.eldercare.edge.mqtt.ManagedMqttClient;
import com.eldercare.edge.storage.EdgeSqliteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 周期指标刷新：Gauge 由内存缓存承载，Prometheus 抓取不直接查询 SQLite；
 * 最长滞后一个刷新周期。
 */
@Component
public class EdgeMetricsRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(EdgeMetricsRefreshTask.class);

    private final EdgeProperties properties;
    private final EdgeSqliteStore store;
    private final EdgeMetrics metrics;
    private final ManagedMqttClient localClient;
    private final ManagedMqttClient cloudClient;

    public EdgeMetricsRefreshTask(EdgeProperties properties, EdgeSqliteStore store,
                                  EdgeMetrics metrics,
                                  @Qualifier("edgeLocalMqttClient") ManagedMqttClient localClient,
                                  @Qualifier("edgeCloudMqttClient") ManagedMqttClient cloudClient) {
        this.properties = properties;
        this.store = store;
        this.metrics = metrics;
        this.localClient = localClient;
        this.cloudClient = cloudClient;
    }

    public void runOnce() {
        try {
            long now = System.currentTimeMillis();
            metrics.localMqttConnected(localClient.isConnected());
            metrics.cloudMqttConnected(cloudClient.isConnected());
            metrics.sqliteUsedBytes(store.usedBytes());
            metrics.unreceiptedP0Notifications(store.countUnreceiptedP0Notifications());
            metrics.queueDepth(store.oldestIngressAgeMillis(now), store.p0PendingDepth(),
                    store.countByStatus(IngressStatus.PENDING),
                    store.countByStatus(IngressStatus.REPLAYING));
        } catch (RuntimeException e) {
            log.warn("edge_metrics_refresh_failed error={}", e.getMessage());
        }
    }
}