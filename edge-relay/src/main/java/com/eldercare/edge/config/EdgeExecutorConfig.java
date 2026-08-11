package com.eldercare.edge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有界执行器与专用调度线程。Paho 回调线程只投递；阻塞 SQLite / 网络发布 /
 * 循环工作不允许占用回调线程或共享调度线程。
 */
@Configuration
public class EdgeExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(EdgeExecutorConfig.class);

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor edgeIngressExecutor(EdgeProperties properties) {
        ThreadFactory factory = namedDaemonFactory("edge-ingress");
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.getExecutor().getIngressThreads(),
                properties.getExecutor().getIngressThreads(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getExecutor().getIngressQueueCapacity()),
                factory, new ThreadPoolExecutor.AbortPolicy());
        log.info("edge_ingress_executor_ready threads={} queue={}",
                properties.getExecutor().getIngressThreads(),
                properties.getExecutor().getIngressQueueCapacity());
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService edgeForwardScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("edge-forward"));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService edgeNotificationScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("edge-notify"));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService edgeMetricsScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("edge-metrics"));
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 优雅关闭兜底（Spring context 关闭时再调用一次）。 */
    public static void quietShutdown(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}