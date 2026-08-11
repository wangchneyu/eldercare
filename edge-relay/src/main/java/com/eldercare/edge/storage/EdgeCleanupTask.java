package com.eldercare.edge.storage;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.metrics.EdgeMetricsRefreshTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 终态清理：只删除 FORWARDED 原始记录与已回执通知（按可配置短审计保留期分批删除）；
 * 绝不删除 PENDING / REPLAYING / CORRUPTED / 未回执的 P0 通知。
 */
@Component
public class EdgeCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(EdgeCleanupTask.class);

    private final EdgeProperties properties;
    private final EdgeSqliteStore store;
    private final EdgeMetricsRefreshTask metricsRefreshTask;

    public EdgeCleanupTask(EdgeProperties properties, EdgeSqliteStore store,
                           EdgeMetricsRefreshTask metricsRefreshTask) {
        this.properties = properties;
        this.store = store;
        this.metricsRefreshTask = metricsRefreshTask;
    }

    public void runOnce() {
        String cutoff = Instant.now()
                .minus(properties.getSqlite().getRetentionHours(), ChronoUnit.HOURS)
                .toString();
        int batchSize = properties.getSqlite().getCleanupBatchSize();
        int rounds = 0;
        int total = 0;
        while (rounds < 5) {
            List<Long> forwarded = store.listForwardedOlderThan(cutoff, batchSize);
            List<Long> receipted = store.listReceiptedOlderThan(cutoff, batchSize);
            List<Long> conflicts = store.listConflictsOlderThan(cutoff, batchSize);
            if (forwarded.isEmpty() && receipted.isEmpty() && conflicts.isEmpty()) {
                break;
            }
            store.deleteForwardedByIds(forwarded);
            store.deleteNotificationsByIds(receipted);
            store.deleteConflictsByIds(conflicts);
            List<Long> orphanIdentities = store.listOrphanIdentities(batchSize);
            store.deleteIdentitiesByIds(orphanIdentities);
            total += forwarded.size() + receipted.size() + conflicts.size() + orphanIdentities.size();
            rounds++;
        }
        if (total > 0) {
            log.info("edge_cleanup_done siteId={} deletedTerminalState={} cutoff={}",
                    properties.getSiteId(), total, cutoff);
            metricsRefreshTask.runOnce();
        }
    }
}
