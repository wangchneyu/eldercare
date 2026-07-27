package com.eldercare.iot.pipeline;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.mq.VitalSignProducer;
import com.eldercare.iot.parser.model.ParsedVitalSign;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 体征批量聚合器：1 秒窗口或 500 条，先到先触发。
 * <p>
 * 聚合后逐条发送到 {@link VitalSignProducer}，保证独立消息与独立幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VitalSignBatchAggregator {

    private static final int MAX_BATCH_SIZE = 500;
    private static final long FLUSH_INTERVAL_MS = 1000L;

    private final VitalSignProducer vitalSignProducer;
    private final Executor iotMqExecutor;
    private final ScheduledExecutorService iotVitalFlushScheduler;

    private final ReentrantLock lock = new ReentrantLock();
    private final List<ParsedVitalSign> buffer = new ArrayList<>();

    @PostConstruct
    public void init() {
        iotVitalFlushScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        flush();
                    } catch (Exception e) {
                        log.error("体征聚合 flush 异常", e);
                    }
                },
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
    }

    public void submit(ParsedVitalSign event) {
        if (event == null) {
            return;
        }
        boolean reachedThreshold;
        lock.lock();
        try {
            buffer.add(event);
            reachedThreshold = buffer.size() >= MAX_BATCH_SIZE;
        } finally {
            lock.unlock();
        }
        if (reachedThreshold) {
            iotMqExecutor.execute(this::flush);
        }
    }

    private void flush() {
        List<ParsedVitalSign> batch;
        lock.lock();
        try {
            if (buffer.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buffer);
            buffer.clear();
        } finally {
            lock.unlock();
        }

        log.debug("体征聚合 flush: {} 条", batch.size());
        for (ParsedVitalSign event : batch) {
            String previousTraceId = TraceContext.currentTraceId();
            try {
                TraceContext.setTraceId(event.traceId());
                vitalSignProducer.send(event);
            } finally {
                if (previousTraceId == null) {
                    TraceContext.clear();
                } else {
                    TraceContext.setTraceId(previousTraceId);
                }
            }
        }
    }
}
