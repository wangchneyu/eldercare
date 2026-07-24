package com.eldercare.iot.mqtt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TopicRouterTest {

    private final TopicRouter router = new TopicRouter();

    @Test
    void valid_topic() {
        Optional<TopicRouter.RouteResult> result = router.route("elder/P001/MATTRESS/DEV-001/up/telemetry");
        assertTrue(result.isPresent());
        assertEquals("P001", result.get().parkId());
        assertEquals("MATTRESS", result.get().deviceType());
        assertEquals("DEV-001", result.get().deviceId());
        assertEquals("telemetry", result.get().messageType());
    }

    @Test
    void valid_sos_event() {
        Optional<TopicRouter.RouteResult> result = router.route("elder/P002/SOS_BUTTON/DEV-SOS-01/up/event");
        assertTrue(result.isPresent());
        assertEquals("SOS_BUTTON", result.get().deviceType());
        assertEquals("event", result.get().messageType());
    }

    @Test
    void valid_heartbeat() {
        Optional<TopicRouter.RouteResult> result = router.route("elder/P001/RADAR/DEV-R01/up/heartbeat");
        assertTrue(result.isPresent());
        assertEquals("RADAR", result.get().deviceType());
        assertEquals("heartbeat", result.get().messageType());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "elder",
        "elder/P001",
        "elder/P001/MATTRESS",
        "elder/P001/MATTRESS/DEV-001",
        "elder/P001/MATTRESS/DEV-001/up",
        "other/P001/MATTRESS/DEV-001/up/telemetry",
        "elder/P001/MATTRESS/DEV-001/down/telemetry",
        "elder/P001/MATTRESS/DEV-001/up/telemetry/extra"
    })
    void invalid_topics(String topic) {
        Optional<TopicRouter.RouteResult> result = router.route(topic);
        assertTrue(result.isEmpty(), "Should reject: " + topic);
    }

    @Test
    void null_topic() {
        assertTrue(router.route(null).isEmpty());
    }
}
