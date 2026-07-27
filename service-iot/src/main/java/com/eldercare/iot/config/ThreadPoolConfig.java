package com.eldercare.iot.config;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 5 独立有界线程池：MQ 发送、P0 发送、重试调度、体征聚合 flush。
 * 解析线程由 DisruptorConfig 提供（iot-parse-*）。
 */
@Configuration
public class ThreadPoolConfig {

    @Bean(name = "iotMqExecutor")
    public Executor iotMqExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("iot-mq-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(name = "iotP0Executor")
    public Executor iotP0Executor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("iot-p0-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(name = "iotRetryScheduler")
    public ScheduledExecutorService iotRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "iot-retry-1");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(name = "iotVitalFlushScheduler")
    public ScheduledExecutorService iotVitalFlushScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "iot-mq-flush-1");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        iotRetryScheduler().shutdownNow();
        iotVitalFlushScheduler().shutdownNow();
    }
}
