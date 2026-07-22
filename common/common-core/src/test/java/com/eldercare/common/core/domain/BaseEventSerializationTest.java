package com.eldercare.common.core.domain;

import com.eldercare.common.core.utils.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * BaseEvent 序列化契约测试
 * <p>
 * 断言 JSON 序列化结果包含全部 6 个信封字段（eventId/eventType/schemaVersion/occurredAt/traceId/producer），
 * 防止字段被误删。
 */
public class BaseEventSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * 测试用事件子类
     */
    static class TestEvent extends BaseEvent<String> {
    }

    @Test
    public void testSerializationContainsAllEnvelopeFields() throws Exception {
        TestEvent event = new TestEvent();
        event.setEventId(IdUtil.uuid32());
        event.setEventType("test_event_v1");
        event.setSchemaVersion("1.0");
        event.setOccurredAt(java.time.ZonedDateTime.now());
        event.setTraceId(IdUtil.uuid32());
        event.setProducer("service-test");
        event.setPayload("hello");

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        // 断言 6 个信封字段齐全
        Assertions.assertTrue(node.has("eventId"), "缺少 eventId 字段");
        Assertions.assertTrue(node.has("eventType"), "缺少 eventType 字段");
        Assertions.assertTrue(node.has("schemaVersion"), "缺少 schemaVersion 字段");
        Assertions.assertTrue(node.has("occurredAt"), "缺少 occurredAt 字段");
        Assertions.assertTrue(node.has("traceId"), "缺少 traceId 字段");
        Assertions.assertTrue(node.has("producer"), "缺少 producer 字段");
        Assertions.assertTrue(node.has("payload"), "缺少 payload 字段");

        // 断言值正确
        Assertions.assertEquals("test_event_v1", node.get("eventType").asText());
        Assertions.assertEquals("1.0", node.get("schemaVersion").asText());
        Assertions.assertEquals("service-test", node.get("producer").asText());
        Assertions.assertEquals("hello", node.get("payload").asText());
        Assertions.assertFalse(node.get("eventId").asText().isEmpty());
        Assertions.assertFalse(node.get("traceId").asText().isEmpty());
    }

    @Test
    public void testEventIdFormat() {
        TestEvent event = new TestEvent();
        event.setEventId(IdUtil.uuid32());
        // UUID32 格式: 32 位无横线十六进制
        Assertions.assertEquals(32, event.getEventId().length());
        Assertions.assertFalse(event.getEventId().contains("-"));
    }
}
