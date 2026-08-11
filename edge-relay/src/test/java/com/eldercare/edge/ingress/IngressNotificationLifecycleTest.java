package com.eldercare.edge.ingress;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.enums.NotificationStatus;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.EnvelopeFields;
import com.eldercare.edge.model.InboundDelivery;
import com.eldercare.edge.model.TerminalNotification;
import com.eldercare.edge.notify.ReceiptProcessor;
import com.eldercare.edge.notify.TerminalNotifier;
import com.eldercare.edge.storage.EdgeSqliteStore;
import com.eldercare.edge.storage.QuotaGuard;
import com.eldercare.edge.support.EdgeTestSupport;
import com.eldercare.edge.support.FakeMqttClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 入站持久化 + 通知创建/发布 + DISPLAYED 回执 生命周期（临时 SQLite，无外部依赖）。
 */
class IngressNotificationLifecycleTest {

    @TempDir
    Path tempDir;

    private EdgeSqliteStore store;
    private EdgeProperties properties;
    private EdgeMetrics metrics;
    private FakeMqttClient localClient;
    private IngressPersistService persistService;
    private QuotaGuard quotaGuard;
    private ReceiptProcessor receiptProcessor;
    private TerminalNotifier notifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = EdgeTestSupport.newStore(tempDir.resolve("lifecycle-" + System.nanoTime() + ".db"));
        properties = EdgeTestSupport.minimalProperties();
        metrics = EdgeTestSupport.metrics();
        localClient = new FakeMqttClient("local");
        notifier = new TerminalNotifier(properties, store, localClient, metrics, objectMapper);
        quotaGuard = new QuotaGuard(properties);
        persistService = new IngressPersistService(
                properties, store, quotaGuard, notifier, metrics);
        receiptProcessor = new ReceiptProcessor(properties, store, metrics, objectMapper);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private int[] process(byte[] payload, String messageType) {
        EnvelopeFields envelope = new EnvelopeFields("P001", "SOS_BUTTON", "DEV-1",
                messageType, "MSG-1", "1.0", "2026-08-10T08:00:00Z");
        InboundDelivery delivery = new InboundDelivery(
                "elder/P001/SOS_BUTTON/DEV-1/up/event", payload, 1, 100);
        boolean ack = persistService.processDeviceUplink(delivery, envelope);
        return new int[]{ack ? 1 : 0};
    }

    @Test
    void sos_persisted_createsNotificationPerConfiguredTerminal_andAcks() throws Exception {
        byte[] payload = ("{\"messageId\":\"MSG-1\",\"deviceId\":\"DEV-1\","
                + "\"messageType\":\"SOS\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"2026-08-10T08:00:00Z\","
                + "\"payload\":{\"triggerType\":\"BUTTON_PRESS\",\"batteryLevel\":85}}")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(process(payload, "SOS")[0] == 1, "SOS 落库成功必须 ACK");

        assertEquals(1, store.countByStatus(com.eldercare.edge.enums.IngressStatus.PENDING));
        assertEquals(2, store.countUnreceiptedP0Notifications(), "两个终端各一条通知");

        TerminalNotification n1 = store.findNotification(
                com.eldercare.edge.util.Sha256Util.notificationId("park-test", "DEV-1",
                        "MSG-1", "SOS", com.eldercare.edge.util.Sha256Util.sha256Hex(payload)),
                "caregiver-01");
        assertNotNull(n1);
        assertEquals(NotificationStatus.BROKER_ACCEPTED, n1.getStatus(), "QoS1 PUBACK 只到 BROKER_ACCEPTED");
        assertNotNull(n1.getBrokerAcceptedAt());

        JsonNode json = objectMapper.readTree(n1.getPayloadJson());
        assertEquals(1, json.path("schemaVersion").asInt());
        assertEquals("SOS", json.path("eventType").asText());
        assertEquals("MSG-1", json.path("sourceMessageId").asText());
        assertEquals("DEV-1", json.path("deviceId").asText());
        assertEquals("SOS_BUTTON", json.path("deviceType").asText());
        assertEquals("P001", json.path("parkId").asText());
        assertEquals("BUTTON_PRESS", json.path("triggerType").asText());
        assertEquals(85, json.path("batteryLevel").asInt());
        assertFalse(json.has("eventId"), "本地通知严禁附带 C05 eventId");
        assertFalse(json.has("elderId"), "本地通知严禁附带 elderId");
        assertFalse(json.has("location"), "本地通知严禁附带位置快照");
        assertFalse(json.has("alertLevel"), "本地通知严禁附带告警等级");

        assertEquals(2, localClient.published().size(), "按终端数逐条 QoS1 发布");
        assertTrue(localClient.published().get(0).topic()
                .startsWith("edge/park-test/caregiver/"), "通知 Topic 必须归属具体终端，不使用广播");
        assertEquals(1, localClient.published().get(0).qos());
    }

    @Test
    void duplicateQosRedelivery_doesNotCreateSecondNotification() {
        byte[] payload = ("{\"messageId\":\"MSG-1\",\"deviceId\":\"DEV-1\","
                + "\"messageType\":\"SOS\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"2026-08-10T08:00:00Z\",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(process(payload, "SOS")[0] == 1);
        long bytesAfterFirstDelivery = quotaGuard.usedBytes();
        assertTrue(process(payload, "SOS")[0] == 1, "QoS 重投也必须 ACK，但不重复入队");
        assertEquals(1, store.countByStatus(com.eldercare.edge.enums.IngressStatus.PENDING));
        assertEquals(2, store.countUnreceiptedP0Notifications(), "重投不重复发本地通知");
        assertEquals(bytesAfterFirstDelivery, quotaGuard.usedBytes(),
                "QoS duplicate must not leak retained-payload quota");
    }

    @Test
    void displayedReceipt_marksReceipted_idempotent_andAcks() throws Exception {
        byte[] payload = ("{\"messageId\":\"MSG-1\",\"deviceId\":\"DEV-1\","
                + "\"messageType\":\"SOS\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"2026-08-10T08:00:00Z\",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        process(payload, "SOS");
        String notificationId = com.eldercare.edge.util.Sha256Util.notificationId(
                "park-test", "DEV-1", "MSG-1", "SOS",
                com.eldercare.edge.util.Sha256Util.sha256Hex(payload));

        String receipt = "{\"schemaVersion\":1,\"notificationId\":\"" + notificationId
                + "\",\"terminalId\":\"caregiver-01\","
                + "\"receivedAt\":\"2026-08-10T08:00:03Z\",\"action\":\"DISPLAYED\"}";
        InboundDelivery delivery = new InboundDelivery(
                "edge/park-test/caregiver/caregiver-01/up/receipt",
                receipt.getBytes(StandardCharsets.UTF_8), 1, 200);
        assertTrue(receiptProcessor.process(delivery, "park-test"));
        assertEquals(NotificationStatus.RECEIPTED,
                store.findNotification(notificationId, "caregiver-01").getStatus());
        assertNotNull(store.findNotification(notificationId, "caregiver-01").getReceiptedAt());
        assertEquals(1, store.countUnreceiptedP0Notifications(), "仅另一终端未回执");

        assertTrue(receiptProcessor.process(delivery, "park-test"), "重复回执幂等且 ACK");
        assertEquals(NotificationStatus.RECEIPTED,
                store.findNotification(notificationId, "caregiver-01").getStatus());
    }

    @Test
    void displayedReceipt_rejectsTerminalIdThatDoesNotMatchTheReceiptTopic() {
        byte[] payload = ("{\"messageId\":\"MSG-1\",\"deviceId\":\"DEV-1\","
                + "\"messageType\":\"SOS\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"2026-08-10T08:00:00Z\",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        process(payload, "SOS");
        String notificationId = com.eldercare.edge.util.Sha256Util.notificationId(
                "park-test", "DEV-1", "MSG-1", "SOS",
                com.eldercare.edge.util.Sha256Util.sha256Hex(payload));
        String receipt = "{\"schemaVersion\":1,\"notificationId\":\"" + notificationId
                + "\",\"terminalId\":\"caregiver-01\",\"action\":\"DISPLAYED\"}";

        assertTrue(receiptProcessor.process(new InboundDelivery(
                "edge/park-test/caregiver/caregiver-02/up/receipt",
                receipt.getBytes(StandardCharsets.UTF_8), 1, 201), "park-test"));
        assertEquals(NotificationStatus.BROKER_ACCEPTED,
                store.findNotification(notificationId, "caregiver-01").getStatus());
    }

    @Test
    void receiptForUnknownTerminalOrUnknownId_rejectedButAcked() {
        String receipt = "{\"schemaVersion\":1,\"notificationId\":\"nope\","
                + "\"terminalId\":\"caregiver-99\","
                + "\"receivedAt\":\"2026-08-10T08:00:03Z\",\"action\":\"DISPLAYED\"}";
        assertTrue(receiptProcessor.process(
                new InboundDelivery("edge/park-test/caregiver/caregiver-99/up/receipt",
                        receipt.getBytes(StandardCharsets.UTF_8), 1, 200), "park-test"));
    }

    @Test
    void p0QuotaExhausted_returnsNoAck_nonP0OverReserveDroppedWithAck() {
        properties.getSqlite().setMaxBytes(100);
        properties.getSqlite().setP0ReserveBytes(50);
        QuotaGuard tight = new QuotaGuard(properties);
        IngressPersistService tightService = new IngressPersistService(
                properties, store, tight, notifier, metrics);

        byte[] sos = ("{\"messageId\":\"MSG-2\",\"deviceId\":\"DEV-1\","
                + "\"messageType\":\"SOS\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"2026-08-10T08:00:00Z\",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(processWith(tightService, sos, "SOS") == 1,
                "P0 配额耗尽：不 ACK，本地 EMQX 持久会话保留消息");

        byte[] vital = ("{\"messageId\":\"MSG-3\",\"deviceId\":\"DEV-1\","
                + "\"messageType\":\"VITAL_SIGN\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"2026-08-10T08:00:01Z\",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(processWith(tightService, vital, "VITAL_SIGN") == 1,
                "非 P0 达到预留阈值：按指标丢弃并 ACK，保护 P0 余量");
    }

    private int processWith(IngressPersistService service, byte[] payload, String messageType) {
        EnvelopeFields envelope = new EnvelopeFields("P001", "SOS_BUTTON", "DEV-1",
                messageType, "MSG-X", "1.0", "2026-08-10T08:00:00Z");
        InboundDelivery delivery = new InboundDelivery(
                "elder/P001/SOS_BUTTON/DEV-1/up/event", payload, 1, 300);
        return service.processDeviceUplink(delivery, envelope) ? 1 : 0;
    }
}
