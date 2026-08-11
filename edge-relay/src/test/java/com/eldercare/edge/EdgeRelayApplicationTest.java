package com.eldercare.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 应用上下文与 Actuator 冒烟：独立部署单元，不注册 Nacos、不连接 PostgreSQL/Redis/RocketMQ。
 * SQLite 使用随机临时文件，避免各测试类文件锁冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "edge.sqlite.path=${java.io.tmpdir}/edge-relay-app-test.db",
        "management.endpoints.web.exposure.include=health,prometheus"
})
@ActiveProfiles("test")
@AutoConfigureObservability
class EdgeRelayApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private com.eldercare.edge.mqtt.ManagedMqttClient edgeLocalMqttClient;

    @Autowired
    private com.eldercare.edge.mqtt.ManagedMqttClient edgeCloudMqttClient;

    @Test
    void contextLoads_withTwoPersistentMqttClients() {
        assertNotNull(edgeLocalMqttClient);
        assertNotNull(edgeCloudMqttClient);
        assertFalse(edgeLocalMqttClient.isConnected(), "测试 profile 不连接真实 Broker");
        assertFalse(edgeCloudMqttClient.isConnected());
    }

    @Test
    void actuatorHealth_up_andPrometheus_hasEdgeGauges() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("UP"), health.body());

        HttpResponse<String> metrics = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("edge_sqlite_used_bytes"),
                "必须暴露 edge_ 前缀指标; BODY=" + metrics.body());
        assertTrue(metrics.body().contains("edge_p0_unreceipted_notifications"));
    }
}