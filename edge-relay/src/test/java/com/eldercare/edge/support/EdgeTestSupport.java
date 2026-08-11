package com.eldercare.edge.support;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Path;
import java.util.List;

/** 测试工具：构造临时 SQLite 库与最小配置。 */
public final class EdgeTestSupport {

    private EdgeTestSupport() {
    }

    public static EdgeSqliteStore newStore(Path db) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + db);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        HikariDataSource dataSource = new HikariDataSource(config);
        EdgeSqliteStore store = new EdgeSqliteStore(dataSource);
        store.initSchema();
        return store;
    }

    public static EdgeProperties minimalProperties() {
        EdgeProperties p = new EdgeProperties();
        p.setSiteId("park-test");
        p.setCaregiverTerminalIds(List.of("caregiver-01", "caregiver-02"));
        EdgeProperties.Mqtt local = new EdgeProperties.Mqtt();
        local.setAutoConnect(false);
        p.setLocalMqtt(local);
        EdgeProperties.Mqtt cloud = new EdgeProperties.Mqtt();
        cloud.setAutoConnect(false);
        p.setCloudMqtt(cloud);
        p.getSqlite().setMaxBytes(1_048_576);
        p.getSqlite().setP0ReserveBytes(262_144);
        p.getNotification().setIntervalMs(10_000);
        return p;
    }

    public static EdgeMetrics metrics() {
        return new EdgeMetrics(new SimpleMeterRegistry());
    }
}