package com.eldercare.common.core.utils;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 可追踪的 Executor 装饰器
 * <p>
 * 包装 Executor / ExecutorService，在任务提交时捕获当前 MDC 上下文（含 traceId），
 * 在任务执行时恢复到子线程 MDC，执行完毕后清理。
 * <p>
 * 使用示例:
 * <pre>{@code
 * Executor traceable = TraceableExecutorDecorator.decorate(executor);
 * CompletableFuture.supplyAsync(() -> {
 *     // 此处 TraceContext.currentTraceId() 返回调用方的 traceId
 *     return doSomething();
 * }, traceable);
 * }</pre>
 */
public final class TraceableExecutorDecorator {

    private TraceableExecutorDecorator() {
    }

    /**
     * 装饰 Executor，使提交的任务自动继承调用方 MDC 上下文
     */
    public static Executor decorate(Executor executor) {
        return command -> executor.execute(wrap(command));
    }

    /**
     * 包装 Runnable，捕获提交时的 MDC 并在执行时恢复
     */
    public static Runnable wrap(Runnable task) {
        // 提交时捕获当前线程的 MDC 上下文
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            // 执行时恢复到子线程
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }
            try {
                task.run();
            } finally {
                // 执行完毕后清理，防止线程池复用导致串号
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
