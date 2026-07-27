package com.eldercare.iot.pipeline;

import com.eldercare.iot.parser.model.RawDeviceMessage;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Disruptor RingBuffer 非阻塞投递封装。
 * <p>
 * MQTT 回调线程通过此类将消息投递到解析管线，全程无 I/O。
 * 当 RingBuffer 满时，{@link #publish(RawDeviceMessage)} 立即返回 {@code false}，
 * 调用方（{@link com.eldercare.iot.mqtt.MqttSubscriber}）不阻塞回调线程，也不 ack MQTT，
 * 依赖 broker 按持久会话重发。
 */
@Slf4j
@Component
public class DisruptorPublisher {

    private final RingBuffer<IotEvent> ringBuffer;

    public DisruptorPublisher(RingBuffer<IotEvent> ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    /**
     * 尝试将消息投递到 RingBuffer。
     *
     * @return {@code true} 表示成功入队；{@code false} 表示 RingBuffer 已满或异常
     */
    public boolean publish(RawDeviceMessage rawMessage) {
        if (rawMessage == null) {
            return false;
        }
        try {
            return ringBuffer.tryPublishEvent((event, sequence, msg) -> event.setRawMessage(msg), rawMessage);
        } catch (Exception e) {
            log.error("Disruptor 投递失败: deviceId={}, traceId={}", rawMessage.deviceId(), rawMessage.traceId(), e);
            return false;
        }
    }
}
