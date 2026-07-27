package com.eldercare.iot.pipeline;

import com.eldercare.iot.parser.model.RawDeviceMessage;

/**
 * Disruptor 事件对象 —— 预先分配的可变对象，用于 RingBuffer 槽位复用。
 */
public class IotEvent {
    private RawDeviceMessage rawMessage;

    public RawDeviceMessage getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(RawDeviceMessage rawMessage) {
        this.rawMessage = rawMessage;
    }

    public void clear() {
        this.rawMessage = null;
    }
}
