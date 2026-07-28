package com.eldercare.iot.mqtt;

import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in test for the versioned local EMQX 5.8.8 security fixture. It never
 * targets the normal anonymous development broker on port 1883.
 */
@EnabledIfSystemProperty(named = "iot.emqx.acl.enabled", matches = "true")
class MqttAclEmqxIntegrationTest {

    private static final String BROKER_URL = System.getProperty("iot.emqx.acl.broker", "tcp://localhost:1885");
    private static final String SERVICE_USER = "iot-service";
    private static final String SERVICE_PASSWORD = "local-only-iot-service-password";
    private static final String DEVICE_ONE = "device-001";
    private static final String DEVICE_ONE_PASSWORD = "local-only-device-001-password";
    private static final String DEVICE_TWO = "device-002";
    private static final String DEVICE_TWO_PASSWORD = "local-only-device-002-password";

    @Test
    void authenticationAndAclRestrictDevicesToOwnTopics() throws Exception {
        assertUnauthenticatedClientIsRejected();

        CountDownLatch ownUplink = new CountDownLatch(1);
        CountDownLatch forbiddenUplink = new CountDownLatch(1);
        CountDownLatch deviceTwoReceivedOwnDownlink = new CountDownLatch(1);

        MqttClient service = connect(SERVICE_USER, SERVICE_PASSWORD);
        MqttClient deviceOne = connect(DEVICE_ONE, DEVICE_ONE_PASSWORD);
        MqttClient deviceTwo = connect(DEVICE_TWO, DEVICE_TWO_PASSWORD);
        try {
            service.setCallback(callback(payload -> {
                if ("own-uplink".equals(payload)) {
                    ownUplink.countDown();
                }
                if ("forbidden-uplink".equals(payload)) {
                    forbiddenUplink.countDown();
                }
            }));
            service.subscribe("elder/+/+/+/up/#", 1);

            deviceTwo.setCallback(callback(payload -> {
                if ("device-two-command".equals(payload)) {
                    deviceTwoReceivedOwnDownlink.countDown();
                }
            }));

            assertThrows(MqttException.class,
                    () -> deviceOne.subscribe("elder/P001/MATTRESS/" + DEVICE_TWO + "/down/#", 1),
                    "device must not subscribe to another device downlink");
            deviceTwo.subscribe("elder/P001/MATTRESS/" + DEVICE_TWO + "/down/#", 1);

            publish(deviceOne, "elder/P001/MATTRESS/" + DEVICE_ONE + "/up/vital", "own-uplink");
            assertTrue(ownUplink.await(5, TimeUnit.SECONDS), "iot-service must receive authorized device uplink");

            publish(deviceOne, "elder/P001/MATTRESS/" + DEVICE_TWO + "/up/vital", "forbidden-uplink");
            assertFalse(forbiddenUplink.await(2, TimeUnit.SECONDS), "device must not publish another device uplink");

            publish(service, "elder/P001/MATTRESS/" + DEVICE_TWO + "/down/command", "device-two-command");
            assertTrue(deviceTwoReceivedOwnDownlink.await(5, TimeUnit.SECONDS), "device must receive its own downlink");
        } finally {
            close(deviceTwo);
            close(deviceOne);
            close(service);
        }
    }

    private void assertUnauthenticatedClientIsRejected() throws Exception {
        MqttClient client = new MqttClient(BROKER_URL, "anonymous-" + UUID.randomUUID(), new MemoryPersistence());
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(3);
            assertThrows(MqttException.class, () -> client.connect(options));
        } finally {
            close(client);
        }
    }

    private MqttClient connect(String username, String password) throws MqttException {
        MqttClient client = new MqttClient(BROKER_URL, username + "-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setConnectionTimeout(5);
        client.connect(options);
        return client;
    }

    private void publish(MqttClient client, String topic, String payload) throws MqttException {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        client.publish(topic, message);
    }

    private MqttCallback callback(java.util.function.Consumer<String> received) {
        return new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                // ACL denials may be configured as an ignored operation; assertions use delivery outcome.
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.accept(new String(message.getPayload(), StandardCharsets.UTF_8));
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
            }
        };
    }

    private void close(MqttClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
            // Cleanup must not hide a security assertion failure.
        }
    }
}
