package com.eldercare.edge.notify;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.enums.NotificationStatus;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.IngressRecord;
import com.eldercare.edge.model.TerminalNotification;
import com.eldercare.edge.mqtt.ManagedMqttClient;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.eldercare.edge.util.Sha256Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地护工终端通知：仅对已持久化的 SOS/FALL 创建本地通知，按配置中的每个终端 ID
 * 单独发布 QoS 1（不使用无归属的广播 Topic）。
 * <p>
 * 通知 JSON 冻结于 edge-relay-design.md §3.2：不得附带 eventId、elderId、绑定/位置快照、
 * 告警等级、派单结果或完整原始 payload。QoS 1 PUBACK 只标记 BROKER_ACCEPTED，
 * 只有匹配终端的 DISPLAYED 回执才进入 RECEIPTED。
 */
@Service
public class TerminalNotifier {

    private static final Logger log = LoggerFactory.getLogger(TerminalNotifier.class);

    private final EdgeProperties properties;
    private final EdgeSqliteStore store;
    private final ManagedMqttClient localClient;
    private final EdgeMetrics metrics;
    private final ObjectMapper objectMapper;

    public TerminalNotifier(EdgeProperties properties, EdgeSqliteStore store,
                            @Qualifier("edgeLocalMqttClient") ManagedMqttClient localClient,
                            EdgeMetrics metrics,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.store = store;
        this.localClient = localClient;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * 构造待通知记录。入站服务会把它们与原始消息放进同一个 SQLite 事务，提交后才调用发布。
     */
    public List<TerminalNotification> buildPendingNotifications(IngressRecord record) {
        String siteId = properties.getSiteId();
        List<TerminalNotification> notifications = new ArrayList<>();
        for (String terminalId : properties.getCaregiverTerminalIds()) {
            String notificationId = Sha256Util.notificationId(
                    siteId, record.getDeviceId(), record.getSourceMessageId(),
                    record.getMessageType(), record.getPayloadSha256());
            String payloadJson = buildFrozenPayload(record, notificationId, siteId);
            TerminalNotification notification = new TerminalNotification();
            notification.setNotificationId(notificationId);
            notification.setTerminalId(terminalId);
            notification.setPayloadJson(payloadJson);
            notification.setStatus(NotificationStatus.PENDING);
            // A restart after commit but before the first publish must be recoverable immediately.
            notification.setNextAttemptAt(System.currentTimeMillis());
            notifications.add(notification);
        }
        return notifications;
    }

    /** 发布已经和原始消息一起提交的本地通知。 */
    public void publishPersistedNotifications(List<TerminalNotification> notifications) {
        String siteId = properties.getSiteId();
        for (TerminalNotification notification : notifications) {
            metrics.notificationCreated(notification.getTerminalId(), siteId);
            publishAndTrack(notification.getId(), notification.getTerminalId(),
                    notification.getNotificationId(), notification.getPayloadJson());
        }
    }

    /**
     * 发布（或重投）同一份通知；成功仅标记 BROKER_ACCEPTED。
     */
    public void publishAndTrack(long id, String terminalId, String notificationId, String payloadJson) {
        String siteId = properties.getSiteId();
        String topic = "edge/" + siteId + "/caregiver/" + terminalId + "/down/alert";
        if (!localClient.isConnected()) {
            store.markNotificationAttempt(id, System.currentTimeMillis()
                    + properties.getNotification().getIntervalMs());
            log.debug("edge_notification_skipped terminalId={} notificationIdPrefix={} reason=local_mqtt_down",
                    terminalId, notificationId.substring(0, Math.min(8, notificationId.length())));
            return;
        }
        try {
            localClient.publish(topic, payloadJson.getBytes(StandardCharsets.UTF_8), 1);
            store.markBrokerAccepted(id, Instant.now().toString(),
                    System.currentTimeMillis() + properties.getNotification().getIntervalMs());
            log.info("edge_notification_broker_accepted siteId={} terminalId={} notificationIdPrefix={} "
                            + "reason=broker_puback_only_not_terminal_display", siteId, terminalId,
                    notificationId.substring(0, Math.min(8, notificationId.length())));
        } catch (MqttException e) {
            store.markNotificationAttempt(id, System.currentTimeMillis()
                    + properties.getNotification().getIntervalMs());
            log.warn("edge_notification_publish_failed siteId={} terminalId={} notificationIdPrefix={} error={}",
                    siteId, terminalId, notificationId.substring(0, Math.min(8, notificationId.length())),
                    e.getMessage());
        }
    }

    /**
     * 冻结通知 JSON（edge-relay-design.md §3.2 样例）；triggerType/batteryLevel 从
     * 设备 payload 可选提取，其他业务字段不补造。
     */
    String buildFrozenPayload(IngressRecord record, String notificationId, String siteId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("notificationId", notificationId);
        node.put("eventType", record.getMessageType());
        node.put("sourceMessageId", record.getSourceMessageId());
        node.put("deviceId", record.getDeviceId());
        node.put("deviceType", topicSegment(record.getTopic(), 2));
        node.put("parkId", topicSegment(record.getTopic(), 1));
        node.put("occurredAt", record.getOccurredAt());
        node.put("receivedAt", record.getReceivedAt());
        try {
            JsonNode envelope = objectMapper.readTree(record.getPayload());
            JsonNode payload = envelope != null ? envelope.path("payload") : null;
            if (payload != null && payload.isObject()) {
                if (payload.hasNonNull("triggerType")) {
                    node.put("triggerType", payload.path("triggerType").asText());
                }
                if (payload.hasNonNull("batteryLevel")) {
                    node.put("batteryLevel", payload.path("batteryLevel").asInt());
                }
            }
        } catch (Exception ignored) {
            // 原始 payload 已通过校验，这里解析失败只影响可选字段
        }
        return node.toString();
    }

    private String topicSegment(String topic, int index) {
        String[] segments = topic.split("/");
        return segments.length > index ? segments[index] : "";
    }
}
