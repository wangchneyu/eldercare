package com.eldercare.iot.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IotMetricsTest {

    private SimpleMeterRegistry registry;
    private IotMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new IotMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void mqttConnectionAndOutboxGaugesExposeCachedRuntimeState() {
        metrics.mqttConnected(true);
        metrics.updateOutboxSnapshot(3, 17L);
        metrics.updateVitalDeliverySnapshot(2, 8L);

        assertEquals(1.0, registry.get("iot_mqtt_connection_count").gauge().value());
        assertEquals(1.0, registry.get("iot_mqtt_reconnect_total").counter().count());
        assertEquals(3.0, registry.get("iot_outbox_pending_count").gauge().value());
        assertEquals(17.0, registry.get("iot_outbox_oldest_age_seconds").gauge().value());
        assertEquals(2.0, registry.get("iot_vital_delivery_pending_count").gauge().value());
        assertEquals(8.0, registry.get("iot_vital_delivery_oldest_age_seconds").gauge().value());

        metrics.mqttDisconnected();
        assertEquals(0.0, registry.get("iot_mqtt_connection_count").gauge().value());
    }

    @Test
    void deviceStatusAndLatencyMetricsExposeExpectedMeters() {
        AtomicLong online = new AtomicLong(2);
        AtomicLong offline = new AtomicLong(1);
        AtomicLong heartbeatAge = new AtomicLong(9);
        metrics.bindDeviceStatusGauges(online::get, offline::get, heartbeatAge::get);
        Timer.Sample p0Timer = metrics.startTimer();
        Timer.Sample mqTimer = metrics.startTimer();

        metrics.recordP0Latency(p0Timer);
        metrics.recordMqSendLatency(mqTimer, "elder-sos-event", "SOS");

        assertEquals(2.0, registry.get("iot_device_online_count").gauge().value());
        assertEquals(1.0, registry.get("iot_device_offline_count").gauge().value());
        assertEquals(9.0, registry.get("iot_heartbeat_oldest_age_seconds").gauge().value());
        assertEquals(1, registry.get("iot_p0_latency_seconds").timer().count());
        assertEquals(1, registry.get("iot_mq_send_latency_seconds").timer().count());
    }

    @Test
    void dynamicSendErrorsUseBoundedMetricTagValues() {
        metrics.mqFailed("elder-vital-raw", "MATTRESS", "broker timeout for device DEV-001");
        metrics.mqttMessageParseFailed("device_parser");

        assertEquals(1.0, registry.get("iot_mq_send_total")
                .tag("reason", "send_exception").counter().count());
        assertEquals(1.0, registry.get("iot_mqtt_parse_failures_total")
                .tag("stage", "device_parser").counter().count());
    }
}
