package com.eldercare.edge.forward;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.enums.IngressStatus;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.EnvelopeFields;
import com.eldercare.edge.model.InboundDelivery;
import com.eldercare.edge.model.IngressRecord;
import com.eldercare.edge.ingress.IngressPersistService;
import com.eldercare.edge.notify.TerminalNotifier;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.eldercare.edge.storage.QuotaGuard;
import com.eldercare.edge.support.EdgeTestSupport;
import com.eldercare.edge.support.FakeMqttClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 云端补传：P0 优先 FIFO、原 Topic/原始字节、失败 at-least-once、checksum 异常不重放。
 * 使用 FakeMqttClient 精确捕获发布的 Topic 与字节，不依赖真实云端 Broker。
 */
class CloudForwardTaskTest {

    @TempDir
    Path tempDir;

    private EdgeSqliteStore store;
    private EdgeProperties properties;
    private FakeMqttClient cloudClient;
    private CloudForwardTask forwardTask;
    private IngressPersistService persistService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = EdgeTestSupport.newStore(tempDir.resolve("forward-" + System.nanoTime() + ".db"));
        properties = EdgeTestSupport.minimalProperties();
        cloudClient = new FakeMqttClient("cloud");
        EdgeMetrics metrics = EdgeTestSupport.metrics();
        TerminalNotifier notifier = new TerminalNotifier(
                properties, store, new FakeMqttClient("local-notify"), metrics, objectMapper);
        persistService = new IngressPersistService(
                properties, store, new QuotaGuard(properties), notifier, metrics);
        forwardTask = new CloudForwardTask(properties, store, cloudClient, metrics);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void publish(String deviceId, String messageId, String type, byte[] payload) {
        persistService.processDeviceUplink(
                new InboundDelivery("elder/P001/SOS_BUTTON/" + deviceId + "/up/event",
                        payload, 1, 400),
                new EnvelopeFields("P001", "SOS_BUTTON", deviceId, type,
                        messageId, "1.0", "2026-08-10T08:00:00Z"));
    }

    @Test
    void replay_usesOriginalTopicOriginalBytesQos1_andMarksForwarded() {
        byte[] payload = ("{\"messageId\":\"m1\",\"deviceId\":\"D1\",\"messageType\":\"SOS\","
                + "\"protocolVersion\":\"1.0\",\"occurredAt\":\"2026-08-10T08:00:00Z\","
                + "\"payload\":{\"triggerType\":\"BUTTON_PRESS\"}}")
                .getBytes(StandardCharsets.UTF_8);
        publish("D1", "m1", "SOS", payload);
        cloudClient.published().clear();
        forwardTask.runOnce();
        assertEquals(1, cloudClient.published().size());
        FakeMqttClient.Published sent = cloudClient.published().get(0);
        assertEquals("elder/P001/SOS_BUTTON/D1/up/event", sent.topic(), "必须复用原始上行 Topic");
        assertArrayEquals(payload, sent.payload(), "必须复用原始 payload 字节");
        assertEquals(1, sent.qos(), "最低按 QoS 1 补传");
        IngressRecord record = store.findIngressById(1L);
        assertEquals(IngressStatus.FORWARDED, record.getStatus());
        assertNotNull(record.getForwardedAt());
        assertEquals(0, store.p0PendingDepth());
    }

    @Test
    void p0ReplayedFirst_whileVitalSignsPending() {
        properties.getForward().setBatchSize(1);
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        publish("D1", "v1", "VITAL_SIGN", "{\"v\":1}".getBytes(StandardCharsets.UTF_8));
        publish("D2", "s1", "SOS", payload);
        publish("D3", "h1", "HEARTBEAT", "{\"h\":1}".getBytes(StandardCharsets.UTF_8));
        forwardTask.runOnce();
        List<FakeMqttClient.Published> sent = cloudClient.published();
        assertEquals(1, sent.size(), "batch=1 时单轮只补传一条");
        assertTrue(sent.get(0).topic().contains("D2"), "P0 SOS 必须优先于体征/心跳");
        assertEquals(IngressStatus.FORWARDED, store.findIngressById(2L).getStatus());
        assertEquals(IngressStatus.PENDING, store.findIngressById(1L).getStatus(),
                "体征不能阻塞 P0，也不承诺跨设备全局业务顺序");
    }

    @Test
    void publishFailure_restoresToPending_atLeastOnce() {
        byte[] payload = ("{\"messageId\":\"m1\",\"deviceId\":\"D1\",\"messageType\":\"SOS\","
                + "\"protocolVersion\":\"1.0\",\"occurredAt\":\"2026-08-10T08:00:00Z\","
                + "\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        publish("D1", "m1", "SOS", payload);
        cloudClient.setFailNextPublish(true);
        forwardTask.runOnce();
        IngressRecord record = store.findIngressById(1L);
        assertEquals(IngressStatus.PENDING, record.getStatus(), "连接中断/结果未知恢复为可重放状态");
        assertNotNull(record.getLastError());
        assertTrue(record.getAttempts() >= 1);
        cloudClient.setFailNextPublish(false);
        forwardTask.runOnce();
        assertEquals(IngressStatus.FORWARDED, store.findIngressById(1L).getStatus(),
                "恢复后同一原始输入继续补传至 FORWARDED");
    }

    @Test
    void corruptedChecksum_marksCorruptedAndNeverReplayed() throws Exception {
        byte[] payload = ("{\"messageId\":\"m1\",\"deviceId\":\"D1\",\"messageType\":\"SOS\","
                + "\"protocolVersion\":\"1.0\",\"occurredAt\":\"2026-08-10T08:00:00Z\","
                + "\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        publish("D1", "m1", "SOS", payload);
        // 落库后篡改 payload 字节 → 重放前 SHA-256 校验必然失败
        IngressRecord record = store.findIngressById(1L);
        store.updateRawPayloadForCorruptionTest(record.getId(), "tampered".getBytes(StandardCharsets.UTF_8));
        forwardTask.runOnce();
        IngressRecord after = store.findIngressById(1L);
        assertEquals(IngressStatus.CORRUPTED, after.getStatus(), "checksum 异常仅保留证据，不重放");
        assertEquals(0, cloudClient.published().size());
    }
}