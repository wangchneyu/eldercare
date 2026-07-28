package com.eldercare.iot.metrics;

import com.eldercare.iot.IntegrationTestConfig;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
class ObservabilityActuatorIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private IotMetrics metrics;

    @Test
    void prometheusEndpointExposesIotMetrics() {
        metrics.mqttConnected(false);
        metrics.mqttMessageParseFailed("device_parser");
        Timer.Sample mqTimer = metrics.startTimer();
        metrics.recordMqSendLatency(mqTimer, "elder-sos-event", "SOS");
        metrics.recordP0Latency(1);

        webTestClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(content -> {
                    assertTrue(content.contains("iot_mqtt_connection_count"));
                    assertTrue(content.contains("iot_mqtt_parse_failures_total"));
                    assertTrue(content.contains("iot_outbox_pending_count"));
                    assertTrue(content.contains("iot_device_online_count"));
                    assertTrue(content.contains("iot_mq_send_latency_seconds"));
                    assertTrue(content.contains("iot_p0_latency_seconds"));
                });
    }
}
