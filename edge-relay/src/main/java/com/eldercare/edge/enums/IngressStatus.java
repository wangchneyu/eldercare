package com.eldercare.edge.enums;

/**
 * Edge 入站原始消息队列状态（冻结于 edge-relay-design.md §4）。
 * 终态清理只允许删除 FORWARDED；PENDING/REPLAYING/CORRUPTED 永不自动清理。
 */
public enum IngressStatus {
    /** 已落库，等待补传。 */
    PENDING,
    /** 正在补传（持有租约），中断后租约过期自动回到可重放状态。 */
    REPLAYING,
    /** 云端 MQTT 已 PUBACK，补传成功。 */
    FORWARDED,
    /** SQLite 中 SHA-256 与落库值不匹配，仅保留证据、绝不重放。 */
    CORRUPTED;

    /** 是否终态：终态清理只允许删除 FORWARDED / 已回执通知。 */
    public boolean terminal() {
        return this == FORWARDED || this == CORRUPTED;
    }
}
