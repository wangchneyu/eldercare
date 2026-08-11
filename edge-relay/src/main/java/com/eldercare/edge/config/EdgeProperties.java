package com.eldercare.edge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Edge Relay 最小配置。所有地址、账号、密码、证书路径和终端 ID 均由环境变量注入。
 */
@ConfigurationProperties(prefix = "edge")
public class EdgeProperties {

    /** 园区标识，进入本地通知 Topic 与通知去重键。 */
    private String siteId = "park-dev";

    private Mqtt localMqtt = new Mqtt();
    private Mqtt cloudMqtt = new Mqtt();

    /** 本园区值班护工终端 ID 静态配置；排班联动属于后续业务集成，本模块不伪造。 */
    private List<String> caregiverTerminalIds = List.of("caregiver-01");

    private Sqlite sqlite = new Sqlite();
    private Forward forward = new Forward();
    private Notification notification = new Notification();
    private Executor executor = new Executor();

    public static class Mqtt {
        /** Broker 地址，如 tcp://localhost:1886 或 ssl://host:8883。 */
        private String brokerUrl = "tcp://localhost:1886";
        private String clientId = "edge-relay-local";
        /** 可选凭据，为空则不启用认证；生产由 P1-12 资产注入，绝不写入仓库。 */
        private String username = "";
        private String password = "";
        private boolean autoConnect = true;
        /** 指数退避重连：基础秒数，翻倍至 max-seconds 上限。 */
        private int reconnectBaseSeconds = 1;
        private int reconnectMaxSeconds = 60;

        public String getBrokerUrl() {
            return brokerUrl;
        }

        public void setBrokerUrl(String brokerUrl) {
            this.brokerUrl = brokerUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isAutoConnect() {
            return autoConnect;
        }

        public void setAutoConnect(boolean autoConnect) {
            this.autoConnect = autoConnect;
        }

        public int getReconnectBaseSeconds() {
            return reconnectBaseSeconds;
        }

        public void setReconnectBaseSeconds(int reconnectBaseSeconds) {
            this.reconnectBaseSeconds = reconnectBaseSeconds;
        }

        public int getReconnectMaxSeconds() {
            return reconnectMaxSeconds;
        }

        public void setReconnectMaxSeconds(int reconnectMaxSeconds) {
            this.reconnectMaxSeconds = reconnectMaxSeconds;
        }
    }

    public static class Sqlite {
        /** SQLite 单文件路径；目录会自动创建。 */
        private String path = "./data/edge-relay.db";
        /** 默认总配额 1 GiB。 */
        private long maxBytes = 1024L * 1024 * 1024;
        /** P0 预留至少 256 MiB；非 P0 在达到 (max - reserve) 上限后按指标丢弃并 ACK。 */
        private long p0ReserveBytes = 256L * 1024 * 1024;
        /** 终态（FORWARDED 原始记录 / 已回执通知）审计保留期，单位小时。 */
        private int retentionHours = 24;
        /** 清理任务每轮批次数与单批上限。 */
        private int cleanupBatchSize = 500;
        /** 清理任务执行间隔毫秒。 */
        private long cleanupIntervalMs = 300_000L;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public long getP0ReserveBytes() {
            return p0ReserveBytes;
        }

        public void setP0ReserveBytes(long p0ReserveBytes) {
            this.p0ReserveBytes = p0ReserveBytes;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public long getCleanupIntervalMs() {
            return cleanupIntervalMs;
        }

        public void setCleanupIntervalMs(long cleanupIntervalMs) {
            this.cleanupIntervalMs = cleanupIntervalMs;
        }
    }

    public static class Forward {
        /** 单轮补传最大条数（P0 优先，同优先级按入队 ID FIFO）。 */
        private int batchSize = 100;
        /** REPLAYING 租约时长秒；中断后超过该时长自动回到可重放状态。 */
        private int leaseSeconds = 60;
        /** 补传调度间隔毫秒。 */
        private long intervalMs = 2_000L;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class Notification {
        /** 未回执通知重投间隔毫秒。 */
        private long intervalMs = 10_000L;
        /** 单轮重投上限。 */
        private int batchSize = 200;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class Executor {
        /** 入站持久化/通知/回执处理线程数（SQLite 单写者由连接池 max=1 串行化）。 */
        private int ingressThreads = 2;
        /** 入站投递有界队列容量；队列满时不再投递（不 ACK，由 Broker 持久会话重发）。 */
        private int ingressQueueCapacity = 4096;
        /** 补传发布线程数（阻塞 publish 不允许进入 MQTT 回调线程）。 */
        private int forwardThreads = 1;

        public int getIngressThreads() {
            return ingressThreads;
        }

        public void setIngressThreads(int ingressThreads) {
            this.ingressThreads = ingressThreads;
        }

        public int getIngressQueueCapacity() {
            return ingressQueueCapacity;
        }

        public void setIngressQueueCapacity(int ingressQueueCapacity) {
            this.ingressQueueCapacity = ingressQueueCapacity;
        }

        public int getForwardThreads() {
            return forwardThreads;
        }

        public void setForwardThreads(int forwardThreads) {
            this.forwardThreads = forwardThreads;
        }
    }

    public String getSiteId() {
        return siteId;
    }

    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }

    public Mqtt getLocalMqtt() {
        return localMqtt;
    }

    public void setLocalMqtt(Mqtt localMqtt) {
        this.localMqtt = localMqtt;
    }

    public Mqtt getCloudMqtt() {
        return cloudMqtt;
    }

    public void setCloudMqtt(Mqtt cloudMqtt) {
        this.cloudMqtt = cloudMqtt;
    }

    public List<String> getCaregiverTerminalIds() {
        return caregiverTerminalIds;
    }

    public void setCaregiverTerminalIds(List<String> caregiverTerminalIds) {
        this.caregiverTerminalIds = caregiverTerminalIds;
    }

    public Sqlite getSqlite() {
        return sqlite;
    }

    public void setSqlite(Sqlite sqlite) {
        this.sqlite = sqlite;
    }

    public Forward getForward() {
        return forward;
    }

    public void setForward(Forward forward) {
        this.forward = forward;
    }

    public Notification getNotification() {
        return notification;
    }

    public void setNotification(Notification notification) {
        this.notification = notification;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }
}
