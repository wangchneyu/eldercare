package com.eldercare.common.core.config;

import com.eldercare.common.core.utils.TraceableExecutorDecorator;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 可追踪的异步配置器
 * <p>
 * 业务服务继承本类并添加 {@code @EnableAsync} 后，{@code @Async} 方法自动使用
 * 装饰后的线程池，traceId 自动从调用方传递到异步线程。
 * <p>
 * 使用示例:
 * <pre>{@code
 * @Configuration
 * @EnableAsync
 * public class AsyncConfig extends TraceableAsyncConfigurer {
 * }
 * }</pre>
 */
public abstract class TraceableAsyncConfigurer implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-trace-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        // 装饰线程池，使 @Async 方法自动继承调用方 traceId
        return TraceableExecutorDecorator.decorate(executor);
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
