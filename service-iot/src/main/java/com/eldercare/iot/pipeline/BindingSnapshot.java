package com.eldercare.iot.pipeline;

import java.util.Map;

/**
 * 设备在消息发生时刻的 ELDER + LOCATION 绑定快照。
 * 换绑后历史事件仍保持该快照不变。
 */
public record BindingSnapshot(
        Long elderId,
        String elderBindingId,
        String parkId,
        String buildingId,
        String roomId,
        String roomNo,
        String locationBindingId,
        Map<String, Object> location
) {
}
