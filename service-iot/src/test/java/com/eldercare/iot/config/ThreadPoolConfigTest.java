package com.eldercare.iot.config;

import com.eldercare.iot.metrics.IotMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ThreadPoolConfig 单元测试：验证有界执行器在队列满时显式拒绝策略生效，
 * 并记录 Micrometer 指标与结构化日志，P0 不被静默丢弃。
 */
@ExtendWith(MockitoExtension.class)
class ThreadPoolConfigTest {

    @Mock
    IotMetrics metrics;

    @Test
    void iotMqExecutor_rejectsWhenQueueFull_andRecordsMetric() throws InterruptedException {
        // 构造一个极容易满的执行器：核心 1、最大 1、队列 0
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("iot-mq-test-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(ThreadPoolConfig.rejectionHandler("iotMqExecutor", metrics));
        executor.initialize();

        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                block.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS), "任务应开始执行");
        // 确保工作线程已卡在 block.await
        Thread.sleep(100);

        // 队列已满，再提交应被拒绝
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        verify(metrics).executorRejected("iotMqExecutor");

        block.countDown();
        executor.shutdown();
    }

    @Test
    void iotP0Executor_rejectsWhenQueueFull_andRecordsMetric() throws InterruptedException {
        // 构造一个极容易满的执行器：核心 1、最大 1、队列 0
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("iot-p0-test-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(ThreadPoolConfig.rejectionHandler("iotP0Executor", metrics));
        executor.initialize();

        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                block.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS), "任务应开始执行");
        // 确保工作线程已卡在 block.await
        Thread.sleep(100);

        // P0 队列满时同样显式拒绝，不静默丢弃
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        verify(metrics).executorRejected("iotP0Executor");

        block.countDown();
        executor.shutdown();
    }
}
