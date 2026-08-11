package com.eldercare.edge.notify;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.mqtt.ManagedMqttClient;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.eldercare.edge.model.TerminalNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 未回执通知重投：对每个终端按固定间隔重投同一份通知和同一 notificationId，
 * 终端按该 ID 去重展示并可重复回执。回执不是云端告警确认。
 */
@Component
public class NotificationRetryTask {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryTask.class);

    private final EdgeProperties properties;
    private final EdgeSqliteStore store;
    private final ManagedMqttClient localClient;
    private final TerminalNotifier notifier;

    public NotificationRetryTask(EdgeProperties properties, EdgeSqliteStore store,
                                 @Qualifier("edgeLocalMqttClient") ManagedMqttClient localClient,
                                 TerminalNotifier notifier) {
        this.properties = properties;
        this.store = store;
        this.localClient = localClient;
        this.notifier = notifier;
    }

    /** 单轮重投（由专用调度线程周期调用）。 */
    public void runOnce() {
        if (!localClient.isConnected()) {
            return;
        }
        List<TerminalNotification> due = store.listNotificationsDue(
                System.currentTimeMillis(), properties.getNotification().getBatchSize());
        for (TerminalNotification notification : due) {
            notifier.publishAndTrack(notification.getId(), notification.getTerminalId(),
                    notification.getNotificationId(), notification.getPayloadJson());
        }
    }
}