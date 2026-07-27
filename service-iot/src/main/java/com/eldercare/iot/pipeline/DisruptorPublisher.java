package com.eldercare.iot.pipeline;

import com.eldercare.iot.parser.model.RawDeviceMessage;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Disruptor RingBuffer 投递封装。
 * MQTT 回调线程通过此类将消息投递到解析管线，全程无 I/O。
 */
@Slf4j
@Component
public class DisruptorPublisher {

    private final RingBuffer<IotEvent> ringBuffer;

    public DisruptorPublisher(RingBuffer<IotEvent> ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    public void publish(RawDeviceMessage rawMessage) {
        if (rawMessage == null) {
            return;
        }
        try {
            ringBuffer.publishEvent((event, sequence, msg) -> event.setRawMessage(msg), rawMessage);
        } catch (Exception e) {
            log.error("Disruptor 投递失败: deviceId={}, traceId={}", rawMessage.deviceId(), rawMessage.traceId(), e);
        }
    }
}
