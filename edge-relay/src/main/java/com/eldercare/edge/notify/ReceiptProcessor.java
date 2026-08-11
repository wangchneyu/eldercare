package com.eldercare.edge.notify;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.InboundDelivery;
import com.eldercare.edge.model.TerminalNotification;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 护工终端展示回执处理。
 * <p>
 * 只有 {@code action=DISPLAYED}、终端 ID 在配置内且通知 ID 存在的回执才把该
 * (notificationId, terminalId) 标记为 RECEIPTED；重复回执幂等。
 * 回执仅证明本地展示，Relay 不向 care-service/alert-service 写任何状态，
 * 不得把 DISPLAYED 写成“告警已创建、已派单、已接单或已到场”。
 */
@Service
public class ReceiptProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReceiptProcessor.class);

    private final EdgeProperties properties;
    private final EdgeSqliteStore store;
    private final EdgeMetrics metrics;
    private final ObjectMapper objectMapper;

    public ReceiptProcessor(EdgeProperties properties, EdgeSqliteStore store,
                            EdgeMetrics metrics, ObjectMapper objectMapper) {
        this.properties = properties;
        this.store = store;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /** 返回 true 表示可以 ACK 该回执消息。 */
    public boolean process(InboundDelivery delivery, String siteId) {
        String topicTerminalId = terminalIdFromReceiptTopic(delivery.topic(), siteId);
        if (topicTerminalId == null) {
            log.warn("edge_receipt_reject siteId={} reason={}", siteId, "receipt_topic_invalid");
            return true;
        }
        JsonNode receipt;
        try {
            receipt = objectMapper.readTree(delivery.payload());
        } catch (Exception e) {
            log.warn("edge_receipt_reject siteId={} reason={}", siteId, "payload_not_json");
            return true;
        }
        if (receipt == null || !receipt.isObject()
                || receipt.path("schemaVersion").asInt() != 1
                || !"DISPLAYED".equals(receipt.path("action").asText())) {
            log.warn("edge_receipt_reject siteId={} reason={}", siteId, "receipt_format_invalid");
            return true;
        }
        String notificationId = receipt.path("notificationId").asText();
        String terminalId = receipt.path("terminalId").asText();
        if (notificationId.isEmpty() || terminalId.isEmpty()
                || !properties.getCaregiverTerminalIds().contains(terminalId)) {
            log.warn("edge_receipt_reject siteId={} terminalId={} reason={}",
                    siteId, terminalId, "terminal_unknown_or_field_missing");
            return true;
        }
        if (!topicTerminalId.equals(terminalId)) {
            log.warn("edge_receipt_reject siteId={} terminalId={} topicTerminalId={} reason={}",
                    siteId, terminalId, topicTerminalId, "terminal_id_topic_mismatch");
            return true;
        }
        TerminalNotification notification = store.findNotification(notificationId, terminalId);
        if (notification == null) {
            log.warn("edge_receipt_unknown siteId={} terminalId={} notificationIdPrefix={} reason={}",
                    siteId, terminalId,
                    notificationId.substring(0, Math.min(8, notificationId.length())),
                    "notification_not_found");
            return true;
        }
        boolean updated = store.markReceipted(notificationId, terminalId, Instant.now().toString());
        metrics.notificationReceipted(terminalId, siteId);
        log.info("edge_receipt_receipted siteId={} terminalId={} notificationIdPrefix={} "
                        + "action=DISPLAYED meaning=local_display_only_no_alert_semantics",
                siteId, terminalId, notificationId.substring(0, Math.min(8, notificationId.length())));
        if (!updated) {
            log.debug("edge_receipt_idempotent siteId={} terminalId={} notificationIdPrefix={}",
                    siteId, terminalId, notificationId.substring(0, Math.min(8, notificationId.length())));
        }
        return true;
    }

    private String terminalIdFromReceiptTopic(String topic, String siteId) {
        String[] segments = topic.split("/", -1);
        if (segments.length != 6
                || !"edge".equals(segments[0])
                || !siteId.equals(segments[1])
                || !"caregiver".equals(segments[2])
                || segments[3].isBlank()
                || !"up".equals(segments[4])
                || !"receipt".equals(segments[5])) {
            return null;
        }
        return segments[3];
    }
}
