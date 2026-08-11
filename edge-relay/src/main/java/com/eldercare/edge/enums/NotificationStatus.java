package com.eldercare.edge.enums;

/**
 * 逐终端本地通知状态。
 * QoS 1 PUBACK 只表示 {@link #BROKER_ACCEPTED}（Broker 已接受，不代表终端已展示）；
 * 收到匹配终端的 {@code action=DISPLAYED} 回执才进入 {@link #RECEIPTED}。
 * 回执仅证明本地展示，绝不代表云端 C05/alert 告警已创建、已派单、已接单或已到场。
 */
public enum NotificationStatus {
    PENDING,
    BROKER_ACCEPTED,
    RECEIPTED
}
