package com.eldercare.iot.config;

import com.eldercare.iot.metrics.IotMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 5 独立有界线程池：MQ 发送、P0 发送、重试调度、体征聚合 flush。
 * 解析线程由 DisruptorConfig 提供（iot-parse-*）。
 * <p>
 * 所有有界执行器必须显式定义拒绝策略，记录结构化日志和 Micrometer 指标，
 * 不得依赖默认策略；P0 被拒绝时不得静默丢弃。
 */
@Configuration
public class ThreadPoolConfig {

    @Bean(name = "iotMqExecutor")
    public Executor iotMqExecutor(IotMetrics metrics) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("iot-mq-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setRejectedExecutionHandler(rejectionHandler("iotMqExecutor", metrics));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(name = "iotP0Executor")
    public Executor iotP0Executor(IotMetrics metrics) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("iot-p0-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(rejectionHandler("iotP0Executor", metrics));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(name = {"iotRetryScheduler", "taskScheduler"}, destroyMethod = "shutdownNow")
    public ScheduledExecutorService iotRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "iot-retry-1");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(name = "iotVitalFlushScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService iotVitalFlushScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> daemonThread(r, "iot-mq-flush-1"));
    }

    @Bean(name = "iotHeartbeatScanScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService iotHeartbeatScanScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> daemonThread(r, "iot-heartbeat-scan-1"));
    }

    @Bean(name = "iotHeartbeatFlushScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService iotHeartbeatFlushScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> daemonThread(r, "iot-heartbeat-flush-1"));
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 显式拒绝策略：记录指标和结构化日志后抛出 RejectedExecutionException，
     * 调用方负责不静默丢弃（如 P0 应依赖 MQTT manual ack + broker 重发）。
     */
    static RejectedExecutionHandler rejectionHandler(String executorName, IotMetrics metrics) {
        return (r, e) -> {
            metrics.executorRejected(executorName);
            throw new RejectedExecutionException("执行器 [" + executorName + "] 队列已满，任务被拒绝");
        };
    }
}
