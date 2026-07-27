package com.eldercare.iot.config;

import com.eldercare.iot.pipeline.DisruptorEventHandler;
import com.eldercare.iot.pipeline.IotEvent;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Configuration
public class DisruptorConfig {

    private static final int RING_BUFFER_SIZE = 16384; // 2^14

    @Bean(destroyMethod = "shutdown")
    public Disruptor<IotEvent> disruptor(DisruptorEventHandler eventHandler) {
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "iot-parse-" + THREAD_NUM.incrementAndGet());
            t.setDaemon(true);
            return t;
        };

        Disruptor<IotEvent> disruptor = new Disruptor<>(
                IotEvent::new,
                RING_BUFFER_SIZE,
                threadFactory,
                ProducerType.MULTI,
                new SleepingWaitStrategy()
        );
        disruptor.handleEventsWith(eventHandler);
        disruptor.start();
        log.info("Disruptor RingBuffer 已启动, size={}", RING_BUFFER_SIZE);
        return disruptor;
    }

    @Bean
    public RingBuffer<IotEvent> ringBuffer(Disruptor<IotEvent> disruptor) {
        return disruptor.getRingBuffer();
    }

    private static final AtomicInteger THREAD_NUM = new AtomicInteger(0);
}
