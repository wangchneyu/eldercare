package com.eldercare.iot.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the Paho MQTT client lifecycle with exponential backoff reconnection.
 * <p>
 * Automatic reconnect is disabled on the Paho client; this class controls
 * reconnection timing (1s → 2s → 4s → 8s → 16s → 32s → 60s cap) via a
 * {@link ScheduledExecutorService}.
 */
@Component
public class MqttConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectionManager.class);

    /** Maximum backoff delay in seconds. */
    private static final int MAX_BACKOFF_SECONDS = 60;

    /** Base backoff delay in seconds. */
    private static final int BASE_BACKOFF_SECONDS = 1;

    @FunctionalInterface
    public interface MessageHandler {
        void handle(String topic, byte[] payload);
    }

    private final MqttConfig mqttConfig;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-reconnect");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger backoffSeconds = new AtomicInteger(BASE_BACKOFF_SECONDS);
    private final AtomicReference<ScheduledFuture<?>> pendingReconnect = new AtomicReference<>();
    private final AtomicReference<MessageHandler> messageHandler = new AtomicReference<>();

    private volatile MqttClient mqttClient;

    public MqttConnectionManager(MqttConfig mqttConfig) {
        this.mqttConfig = mqttConfig;
    }

    // ──────────────────────────── Public API ────────────────────────────

    /**
     * Create the underlying {@link MqttClient} (if not already created) and
     * connect to the broker. This method is idempotent and thread-safe.
     */
    public synchronized void connect() {
        if (!mqttConfig.isAutoConnect()) {
            log.info("MQTT auto-connect disabled, skipping connection");
            return;
        }
        try {
            if (mqttClient == null) {
                mqttClient = new MqttClient(mqttConfig.getBrokerUrl(),
                        mqttConfig.getClientId(), new MemoryPersistence());
                mqttClient.setCallback(new InternalCallback());
            }

            if (mqttClient.isConnected()) {
                log.debug("MQTT client already connected to {}", mqttConfig.getBrokerUrl());
                return;
            }

            MqttConnectOptions options = buildConnectOptions();
            log.info("Connecting to MQTT broker at {} ...", mqttConfig.getBrokerUrl());
            mqttClient.connect(options);
        } catch (MqttException e) {
            log.error("Failed to connect to MQTT broker: {}", e.getMessage(), e);
            scheduleReconnect();
        }
    }

    /**
     * Gracefully disconnect from the broker and release resources.
     */
    public synchronized void disconnect() {
        cancelPendingReconnect();
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                connected.set(false);
                log.info("Disconnected from MQTT broker");
            } catch (MqttException e) {
                log.warn("Error while disconnecting MQTT client: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * @return {@code true} if the client is currently connected.
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * @return the underlying Paho {@link MqttClient}, or {@code null} if
     *         {@link #connect()} has not been called yet.
     */
    public MqttClient getClient() {
        return mqttClient;
    }

    /**
     * Register a handler that will be invoked for every incoming message.
     * Intended to be called by the subscriber component.
     */
    public void setMessageHandler(MessageHandler handler) {
        this.messageHandler.set(handler);
    }

    // ──────────────────────────── Lifecycle ─────────────────────────────

    @PreDestroy
    public void destroy() {
        log.info("Shutting down MQTT connection manager ...");
        cancelPendingReconnect();
        try {
            if (mqttClient != null) {
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                mqttClient.close();
            }
        } catch (MqttException e) {
            log.warn("Error during MQTT shutdown: {}", e.getMessage(), e);
        } finally {
            connected.set(false);
            scheduler.shutdownNow();
            log.info("MQTT connection manager shut down");
        }
    }

    // ──────────────────────── Internal helpers ──────────────────────────

    private MqttConnectOptions buildConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setAutomaticReconnect(false);

        String username = mqttConfig.getUsername();
        String password = mqttConfig.getPassword();
        if (username != null && !username.isEmpty()) {
            options.setUserName(username);
        }
        if (password != null && !password.isEmpty()) {
            options.setPassword(password.toCharArray());
        }

        return options;
    }

    private void scheduleReconnect() {
        if (scheduler.isShutdown()) {
            log.debug("Scheduler is shut down; skipping reconnect");
            return;
        }

        cancelPendingReconnect();

        int delay = backoffSeconds.get();
        log.warn("Scheduling MQTT reconnect in {} second(s) ...", delay);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            log.info("Attempting MQTT reconnect (backoff was {}s) ...", delay);
            connect();
        }, delay, TimeUnit.SECONDS);

        pendingReconnect.set(future);

        // Prepare next backoff value (double, capped at MAX_BACKOFF_SECONDS)
        int next = Math.min(delay * 2, MAX_BACKOFF_SECONDS);
        backoffSeconds.set(next);
    }

    private void cancelPendingReconnect() {
        ScheduledFuture<?> future = pendingReconnect.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    private void subscribeToTopics() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            return;
        }

        String[] topics = mqttConfig.getTopics();
        int[] qos = mqttConfig.getQos();

        // Ensure qos array matches topics length
        int[] effectiveQos = new int[topics.length];
        for (int i = 0; i < topics.length; i++) {
            effectiveQos[i] = (qos != null && i < qos.length) ? qos[i] : 1;
        }

        try {
            mqttClient.subscribe(topics, effectiveQos);
            for (int i = 0; i < topics.length; i++) {
                log.info("Subscribed to topic '{}' with QoS {}", topics[i], effectiveQos[i]);
            }
        } catch (MqttException e) {
            log.error("Failed to subscribe to topics: {}", e.getMessage(), e);
        }
    }

    // ───────────────────── Paho callback implementation ─────────────────

    /**
     * Uses {@link MqttCallbackExtended} so that {@code connectComplete} is
     * invoked on both initial connect and reconnect, allowing us to
     * re-subscribe after a reconnect.
     */
    private class InternalCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            connected.set(true);
            backoffSeconds.set(BASE_BACKOFF_SECONDS);
            cancelPendingReconnect();

            if (reconnect) {
                log.info("Reconnected to MQTT broker at {}", serverURI);
            } else {
                log.info("Connected to MQTT broker at {}", serverURI);
            }

            // (Re-)subscribe after every (re)connect
            subscribeToTopics();
        }

        @Override
        public void connectionLost(Throwable cause) {
            connected.set(false);
            log.warn("MQTT connection lost: {}", cause.getMessage());
            scheduleReconnect();
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            MessageHandler handler = messageHandler.get();
            if (handler != null) {
                try {
                    handler.handle(topic, message.getPayload());
                } catch (Exception e) {
                    log.error("Error in message handler for topic '{}': {}", topic, e.getMessage(), e);
                }
            } else {
                log.debug("No message handler registered; dropping message on topic '{}'", topic);
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Nothing to do for a subscriber-oriented connection manager
        }
    }
}
