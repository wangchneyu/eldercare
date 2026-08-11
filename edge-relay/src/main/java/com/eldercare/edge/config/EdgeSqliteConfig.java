package com.eldercare.edge.config;

import com.eldercare.edge.storage.EdgeSqliteStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 初始化：单实例文件锁（fail-fast）、WAL、synchronous=FULL、启动完整性检查、DDL。
 * <p>
 * Hikari 池固定 single-connection（maximum-pool-size=1），保证写者串行，
 * SQLite 不会出现多连接写锁竞争。
 */
@Configuration
@ConditionalOnProperty(prefix = "edge.sqlite", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EdgeSqliteConfig {

    private static final Logger log = LoggerFactory.getLogger(EdgeSqliteConfig.class);

    private FileLock lockFileLock;

    @Bean(destroyMethod = "close")
    public DataSource edgeSqliteDataSource(EdgeProperties properties) {
        EdgeProperties.Sqlite sqlite = properties.getSqlite();
        Path dbPath = Path.of(sqlite.getPath());
        try {
            Files.createDirectories(dbPath.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 SQLite 数据目录: " + dbPath.toAbsolutePath().getParent(), e);
        }

        acquireExclusiveLock(dbPath);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
            }
            verifyIntegrity(connection, dbPath);
        } catch (SQLException e) {
            releaseLock();
            throw new IllegalStateException("SQLite 初始化失败: " + dbPath, e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA busy_timeout=5000");
        config.setPoolName("edge-sqlite");
        config.setConnectionTimeout(10_000);
        config.setValidationTimeout(5_000);
        return new HikariDataSource(config);
    }

    @Bean
    public EdgeSqliteStore edgeSqliteStore(DataSource edgeSqliteDataSource) {
        EdgeSqliteStore store = new EdgeSqliteStore(edgeSqliteDataSource);
        store.initSchema();
        log.info("edge_sqlite_ready wal=on synchronous=FULL");
        return store;
    }

    private void acquireExclusiveLock(Path dbPath) {
        Path lockPath = Path.of(dbPath.toString() + ".lock");
        try {
            FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IllegalStateException(
                        "SQLite 已被另一个 edge-relay 实例占用（fail-fast）: " + lockPath);
            }
            lockFileLock = lock;
        } catch (OverlappingFileLockException e) {
            throw new IllegalStateException("当前 JVM 已持有 SQLite 文件锁: " + lockPath, e);
        } catch (IOException e) {
            throw new IllegalStateException("无法获取 SQLite 文件锁: " + lockPath, e);
        }
    }

    private void verifyIntegrity(Connection connection, Path dbPath) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            String resultText = result.next() ? result.getString(1) : "unknown";
            if (!"ok".equals(resultText)) {
                throw new IllegalStateException("SQLite 完整性检查失败: " + resultText + " 文件=" + dbPath);
            }
        }
        log.info("edge_sqlite_integrity_check ok file={}", dbPath);
    }

    private void releaseLock() {
        if (lockFileLock != null) {
            try {
                lockFileLock.release();
            } catch (IOException ignored) {
            }
        }
    }
}
