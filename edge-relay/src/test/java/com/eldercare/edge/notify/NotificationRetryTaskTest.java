package com.eldercare.edge.notify;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.enums.NotificationStatus;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.TerminalNotification;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.eldercare.edge.support.EdgeTestSupport;
import com.eldercare.edge.support.FakeMqttClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 未回执通知按固定间隔重投同一份通知与同一 notificationId。 */
class NotificationRetryTaskTest {

    @TempDir
    Path tempDir;

    private EdgeSqliteStore store;
    private EdgeProperties properties;
    private FakeMqttClient localClient;
    private NotificationRetryTask retryTask;

    @BeforeEach
    void setUp() {
        store = EdgeTestSupport.newStore(tempDir.resolve("retry-" + System.nanoTime() + ".db"));
        properties = EdgeTestSupport.minimalProperties();
        properties.getNotification().setIntervalMs(5_000);
        localClient = new FakeMqttClient("local");
        EdgeMetrics metrics = EdgeTestSupport.metrics();
        TerminalNotifier notifier = new TerminalNotifier(
                properties, store, localClient, metrics, new ObjectMapper());
        retryTask = new NotificationRetryTask(properties, store, localClient, notifier);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private TerminalNotification notification(String id, long messageId) {
        TerminalNotification n = new TerminalNotification();
        n.setNotificationId(id);
        n.setMessageId(messageId);
        n.setTerminalId("caregiver-01");
        n.setPayloadJson("{\"schemaVersion\":1,\"notificationId\":\"" + id + "\"}");
        n.setStatus(NotificationStatus.BROKER_ACCEPTED);
        n.setNextAttemptAt(0);
        return n;
    }

    @Test
    void unreceiptedNotification_isResentWithSameNotificationIdAndPayload() {
        store.insertNotification(notification("n1", 1L));
        TerminalNotification first = store.findNotification("n1", "caregiver-01");
        String payloadBefore = first.getPayloadJson();

        retryTask.runOnce();
        TerminalNotification after = store.findNotification("n1", "caregiver-01");
        assertEquals("n1", after.getNotificationId(), "重投必须使用同一 notificationId");
        assertEquals(payloadBefore, after.getPayloadJson(), "重投必须使用同一份通知 JSON");
        assertEquals(1, localClient.published().size());
        assertEquals(NotificationStatus.BROKER_ACCEPTED, after.getStatus());
        assertNotNull(after.getBrokerAcceptedAt(), "PUBACK 刷新 BROKER_ACCEPTED 时间");
    }

    @Test
    void receiptedNotification_isNotResent() {
        store.insertNotification(notification("n1", 1L));
        store.markReceipted("n1", "caregiver-01", "2026-08-10T08:00:05Z");
        localClient.published().clear();
        retryTask.runOnce();
        assertEquals(0, localClient.published().size(), "已回执通知不再重投");
    }
}