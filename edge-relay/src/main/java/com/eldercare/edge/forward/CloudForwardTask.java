package com.eldercare.edge.forward;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.IngressRecord;
import com.eldercare.edge.mqtt.ManagedMqttClient;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.eldercare.edge.util.Sha256Util;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 云端恢复补传：连接云端 EMQX 且 QoS 1 PUBACK 后才标记 FORWARDED。
 * <p>
 * 重放必须复用原始上行 Topic、原始 payload 字节、原始 QoS（最低按 QoS 1）；
 * 不重组 JSON、不补写时间、不替换 messageId，不直写 RocketMQ，不生成 C04/C05 eventId。
 * 调度策略为 P0 优先、同一优先级按 SQLite 入队 ID FIFO（claimReplayable 的
 * ORDER BY priority ASC, id ASC）。事实时间以 occurredAt 为准。
 */
@Component
public class CloudForwardTask {

    private static final Logger log = LoggerFactory.getLogger(CloudForwardTask.class);

    private final EdgeProperties properties;
    private final EdgeSqliteStore store;
    private final ManagedMqttClient cloudClient;
    private final EdgeMetrics metrics;

    public CloudForwardTask(EdgeProperties properties, EdgeSqliteStore store,
                            @Qualifier("edgeCloudMqttClient") ManagedMqttClient cloudClient,
                            EdgeMetrics metrics) {
        this.properties = properties;
        this.store = store;
        this.cloudClient = cloudClient;
        this.metrics = metrics;
    }

    /** 单轮补传（由专用调度线程周期调用）。 */
    public void runOnce() {
        String siteId = properties.getSiteId();
        if (!cloudClient.isConnected()) {
            metrics.cloudMqttConnected(false);
            log.debug("edge_cloud_forward_skipped reason=cloud_mqtt_disconnected");
            return;
        }
        metrics.cloudMqttConnected(true);
        long now = System.currentTimeMillis();
        long leaseUntil = now + properties.getForward().getLeaseSeconds() * 1000L;
        List<IngressRecord> claimed = store.claimReplayable(
                properties.getForward().getBatchSize(), leaseUntil, now);
        for (IngressRecord record : claimed) {
            forwardOne(record);
        }
    }

    private void forwardOne(IngressRecord record) {
        String siteId = properties.getSiteId();
        String actualHash = Sha256Util.sha256Hex(record.getPayload());
        if (!actualHash.equals(record.getPayloadSha256())) {
            store.markCorrupted(record.getId(), "sha256_mismatch");
            metrics.corrupted(siteId);
            log.error("edge_cloud_forward_corrupted siteId={} deviceId={} sourceMessageId={} "
                            + "messageType={} id={} reason={} evidence_kept_no_replay",
                    siteId, record.getDeviceId(), record.getSourceMessageId(),
                    record.getMessageType(), record.getId(), "sha256_mismatch");
            return;
        }
        try {
            cloudClient.publish(record.getTopic(), record.getPayload(), 1);
            if (store.markForwarded(record.getId(), Instant.now().toString())) {
                metrics.cloudForwardSuccess(siteId);
                log.info("edge_cloud_forward_success siteId={} deviceId={} sourceMessageId={} "
                                + "messageType={} id={} topic={} hashPrefix={} replay=original_topic_original_bytes_qos1",
                        siteId, record.getDeviceId(), record.getSourceMessageId(),
                        record.getMessageType(), record.getId(), record.getTopic(),
                        record.getPayloadSha256().substring(0, Math.min(8, record.getPayloadSha256().length())));
            }
        } catch (MqttException e) {
            store.markReplayFailure(record.getId(), e.getMessage());
            metrics.cloudForwardFailure(siteId);
            log.warn("edge_cloud_forward_failed siteId={} deviceId={} sourceMessageId={} "
                            + "messageType={} id={} error={} retry=at_least_once_original_input",
                    siteId, record.getDeviceId(), record.getSourceMessageId(),
                    record.getMessageType(), record.getId(), e.getMessage());
        }
    }
}