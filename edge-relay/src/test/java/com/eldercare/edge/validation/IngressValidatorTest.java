package com.eldercare.edge.validation;

import com.eldercare.edge.model.EnvelopeFields;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 入站轻量校验：六段 Topic、身份一致、必填字段、Topic 与类型匹配、64 KiB 上限。 */
class IngressValidatorTest {

    private final IngressValidator validator = new IngressValidator(new ObjectMapper());

    private byte[] envelope(String deviceId, String messageId, String type, String occurredAt) {
        return ("{\"messageId\":\"" + messageId + "\",\"deviceId\":\"" + deviceId
                + "\",\"messageType\":\"" + type + "\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"" + occurredAt + "\",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validEvents_parse() {
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/event",
                envelope("DEV-1", "m1", "SOS", "2026-08-10T08:00:00Z")).isPresent());
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/event",
                envelope("DEV-1", "m2", "FALL", "2026-08-10T08:00:00Z")).isPresent());
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/MATTRESS/DEV-2/up/telemetry",
                envelope("DEV-2", "m3", "VITAL_SIGN", "2026-08-10T08:00:00Z")).isPresent());
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/WATCH/DEV-3/up/heartbeat",
                envelope("DEV-3", "m4", "HEARTBEAT", "2026-08-10T08:00:00Z")).isPresent());
    }

    @Test
    void parseReturnsParkIdDeviceType() {
        Optional<EnvelopeFields> parsed = validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/event",
                envelope("DEV-1", "m1", "SOS", "2026-08-10T08:00:00Z"));
        assertTrue(parsed.isPresent());
        assertEquals("P001", parsed.get().parkId());
        assertEquals("SOS_BUTTON", parsed.get().deviceType());
    }

    @Test
    void rejects_badTopicShape_orDeviceMismatch_orTypeMismatch_orMissingFields() {
        byte[] ok = envelope("DEV-1", "m1", "SOS", "2026-08-10T08:00:00Z");
        assertTrue(validator.validateDeviceUplink("elder/P001/SOS_BUTTON/DEV-1/up", ok).isEmpty(),
                "缺第六段拒绝");
        assertTrue(validator.validateDeviceUplink("elder/P001/SOS_BUTTON/DEV-9/up/event", ok).isEmpty(),
                "Topic 与信封 deviceId 不一致拒绝");
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/heartbeat", ok).isEmpty(),
                "Topic 末段 heartbeat 与信封 SOS 不匹配拒绝");
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/event",
                envelope("DEV-1", "m1", "VITAL_SIGN", "2026-08-10T08:00:00Z")).isEmpty(),
                "event 末段只能承载 SOS/FALL");
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/event",
                envelope("DEV-1", "", "SOS", "2026-08-10T08:00:00Z")).isEmpty(),
                "messageId 为空拒绝");
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/event", "not-json".getBytes()).isEmpty(),
                "非 JSON payload 拒绝");
    }

    @Test
    void rejects_payloadAbove64KiB() {
        byte[] big = envelope("DEV-1", "m1", "SOS", "2026-08-10T08:00:00Z");
        byte[] padded = new byte[IngressValidator.MAX_PAYLOAD_BYTES + 1];
        System.arraycopy(big, 0, padded, 0, big.length);
        assertTrue(validator.validateDeviceUplink(
                "elder/P001/SOS_BUTTON/DEV-1/up/event", padded).isEmpty(),
                "超过 64 KiB 拒绝");
    }
}