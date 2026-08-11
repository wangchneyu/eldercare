package com.eldercare.edge.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in Docker evidence for the local EMQX -> edge-relay -> cloud EMQX recovery path.
 * It deliberately stops only the local compose cloud fixture and never talks to shared services.
 */
@EnabledIfSystemProperty(named = "edge.e2e.enabled", matches = "true")
class EdgeRelayRecoveryE2eTest {

    private static final String LOCAL_BROKER = "tcp://localhost:1886";
    private static final String CLOUD_BROKER = "tcp://localhost:1887";
    private static final String SITE_ID = "park-site-01";
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MqttClient localPublisher;
    private MqttClient localTerminal;
    private MqttClient cloudObserver;
    private boolean cloudStopped;

    @AfterEach
    void restoreEnvironment() throws Exception {
        if (cloudStopped) {
            runCompose("start", "cloud-emqx");
        }
        closeQuietly(localPublisher);
        closeQuietly(localTerminal);
        closeQuietly(cloudObserver);
    }

    @Test
    void sosIsNotifiedLocallyDuringCloudOutageThenReplayedWithOriginalTopicAndBytes() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String deviceId = "E2E-SOS-" + suffix.substring(0, 10);
        String sourceMessageId = "edge-e2e-" + suffix;
        String topic = "elder/1988123456789012301/SOS_BUTTON/" + deviceId + "/up/event";
        byte[] sourcePayload = sourcePayload(deviceId, sourceMessageId);

        CountDownLatch notificationReceived = new CountDownLatch(1);
        AtomicReference<JsonNode> notification = new AtomicReference<>();
        localTerminal = newClient(LOCAL_BROKER, "edge-e2e-terminal-" + suffix);
        localTerminal.setCallback(callback((receivedTopic, message) -> {
            try {
                notification.set(objectMapper.readTree(message.getPayload()));
                notificationReceived.countDown();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        connect(localTerminal, true);
        localTerminal.subscribe("edge/" + SITE_ID + "/caregiver/caregiver-01/down/alert", 1);

        CountDownLatch replayReceived = new CountDownLatch(1);
        AtomicReference<byte[]> replayPayload = new AtomicReference<>();
        AtomicReference<String> replayTopic = new AtomicReference<>();
        cloudObserver = newClient(CLOUD_BROKER, "edge-e2e-observer-" + suffix);
        cloudObserver.setCallback(callback((receivedTopic, message) -> {
            replayTopic.set(receivedTopic);
            replayPayload.set(message.getPayload());
            replayReceived.countDown();
        }));
        connect(cloudObserver, false);
        cloudObserver.subscribe(topic, 1);

        runCompose("stop", "cloud-emqx");
        cloudStopped = true;

        localPublisher = newClient(LOCAL_BROKER, "edge-e2e-device-" + suffix);
        connect(localPublisher, true);
        MqttMessage inbound = new MqttMessage(sourcePayload);
        inbound.setQos(1);
        localPublisher.publish(topic, inbound);

        assertTrue(notificationReceived.await(RECEIVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "local caregiver notification must be available while cloud MQTT is down");
        JsonNode frozenNotification = notification.get();
        assertNotNull(frozenNotification);
        assertEquals(1, frozenNotification.path("schemaVersion").asInt());
        assertEquals("SOS", frozenNotification.path("eventType").asText());
        assertEquals(sourceMessageId, frozenNotification.path("sourceMessageId").asText());
        assertEquals(deviceId, frozenNotification.path("deviceId").asText());
        assertEquals("SOS_BUTTON", frozenNotification.path("deviceType").asText());
        assertEquals("1988123456789012301", frozenNotification.path("parkId").asText());

        closeQuietly(cloudObserver);
        cloudObserver = null;
        runCompose("start", "cloud-emqx");
        cloudStopped = false;

        cloudObserver = newClient(CLOUD_BROKER, "edge-e2e-observer-" + suffix);
        cloudObserver.setCallback(callback((receivedTopic, message) -> {
            replayTopic.set(receivedTopic);
            replayPayload.set(message.getPayload());
            replayReceived.countDown();
        }));
        connectEventually(cloudObserver, Duration.ofSeconds(20));
        cloudObserver.subscribe(topic, 1);

        assertTrue(replayReceived.await(RECEIVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "cloud MQTT must receive the cached SOS after broker recovery");
        assertEquals(topic, replayTopic.get());
        assertArrayEquals(sourcePayload, replayPayload.get());
    }

    private byte[] sourcePayload(String deviceId, String sourceMessageId) {
        String json = "{\"messageId\":\"" + sourceMessageId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"messageType\":\"SOS\",\"protocolVersion\":\"1.0\","
                + "\"occurredAt\":\"" + Instant.now() + "\","
                + "\"payload\":{\"triggerType\":\"BUTTON_PRESS\",\"batteryLevel\":80}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private MqttClient newClient(String broker, String clientId) throws MqttException {
        return new MqttClient(broker, clientId, new MemoryPersistence());
    }

    private void connect(MqttClient client, boolean cleanSession) throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(cleanSession);
        options.setConnectionTimeout(5);
        client.connect(options);
    }

    private void connectEventually(MqttClient client, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        MqttException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                connect(client, false);
                return;
            } catch (MqttException e) {
                lastFailure = e;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("cloud EMQX did not become reachable", lastFailure);
    }

    private MqttCallback callback(MessageConsumer consumer) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                // The test explicitly reconnects the cloud observer after the fault window.
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                consumer.accept(topic, message);
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
            }
        };
    }

    private void runCompose(String... args) throws IOException, InterruptedException {
        Path composeFile = Path.of(System.getProperty("edge.e2e.compose-file",
                "deploy/docker-compose.yml")).toAbsolutePath();
        String[] command = new String[args.length + 4];
        command[0] = "docker";
        command[1] = "compose";
        command[2] = "-f";
        command[3] = composeFile.toString();
        System.arraycopy(args, 0, command, 4, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("docker compose command failed: " + output);
        }
    }

    private void closeQuietly(MqttClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnectForcibly(1_000, 1_000, false);
            }
            client.close();
        } catch (MqttException ignored) {
        }
    }

    @FunctionalInterface
    private interface MessageConsumer {
        void accept(String topic, MqttMessage message);
    }
}
