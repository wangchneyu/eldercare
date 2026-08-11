package com.eldercare.edge.mqtt;

import com.eldercare.edge.config.EdgeProperties;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 单路 Paho 客户端生命周期管理：指数退避重连（base → ×2 → max 上限）、
 * manualAcks、cleanSession=false 持久会话。回调线程只投递或 ACK，
 * 阻塞 SQLite / 网络重发 / 循环工作一律在调用方工作线程执行。
 */
public class ManagedMqttClient {

    private static final Logger log = LoggerFactory.getLogger(ManagedMqttClient.class);

    public interface MessageSink {
        void handle(String topic, byte[] payload, int qos, int pahoMessageId);
    }

    private final String role;
    private final String brokerUrl;
    private final String clientId;
    private final String username;
    private final String password;
    private final boolean autoConnect;
    private final int baseBackoffSeconds;
    private final int maxBackoffSeconds;
    private final String[] subscribeTopics;
    private final int[] subscribeQos;
    private volatile MessageSink messageSink;

    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger backoffSeconds = new AtomicInteger(1);
    private final AtomicReference<ScheduledFuture<?>> pendingReconnect = new AtomicReference<>();
    private volatile MqttClient mqttClient;

    public ManagedMqttClient(String role, EdgeProperties.Mqtt config,
                             String[] subscribeTopics, int[] subscribeQos,
                             MessageSink messageSink) {
        this.role = role;
        this.brokerUrl = config.getBrokerUrl();
        this.clientId = config.getClientId();
        this.username = config.getUsername();
        this.password = config.getPassword();
        this.autoConnect = config.isAutoConnect();
        this.baseBackoffSeconds = config.getReconnectBaseSeconds();
        this.maxBackoffSeconds = config.getReconnectMaxSeconds();
        this.subscribeTopics = subscribeTopics;
        this.subscribeQos = subscribeQos;
        this.messageSink = messageSink;
        this.backoffSeconds.set(baseBackoffSeconds);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "edge-mqtt-reconnect-" + role);
            t.setDaemon(true);
            return t;
        });
    }

    /** 注册消息接收器（本地客户端由入站处理器在构造完成后自注册，避免循环依赖）。 */
    public void setMessageSink(MessageSink messageSink) {
        this.messageSink = messageSink;
    }

    public synchronized void connect() {
        if (!autoConnect) {
            log.info("edge_mqtt role={} auto-connect disabled", role);
            return;
        }
        try {
            if (mqttClient == null) {
                mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                mqttClient.setCallback(new InternalCallback());
                mqttClient.setManualAcks(true);
            }
            if (mqttClient.isConnected()) {
                return;
            }
            log.info("edge_mqtt_connecting role={} broker={} clientId={}", role, brokerUrl, clientId);
            mqttClient.connect(buildConnectOptions());
        } catch (MqttException e) {
            log.warn("edge_mqtt_connect_failed role={} broker={} error={}", role, brokerUrl, e.getMessage());
            scheduleReconnect();
        }
    }

    public synchronized void disconnect() {
        cancelPendingReconnect();
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
            } catch (MqttException e) {
                log.warn("edge_mqtt_disconnect_failed role={} error={}", role, e.getMessage());
            }
        }
        connected.set(false);
    }

    public boolean isConnected() {
        return connected.get();
    }

    /** 手动确认一条入站消息（由工作线程在 SQLite 事务提交后调用）。 */
    public void ack(int pahoMessageId, int qos) {
        MqttClient client = mqttClient;
        if (client == null || !client.isConnected()) {
            log.debug("edge_mqtt_ack_skipped role={} messageId={} reason=not_connected", role, pahoMessageId);
            return;
        }
        try {
            client.messageArrivedComplete(pahoMessageId, qos);
        } catch (MqttException e) {
            log.warn("edge_mqtt_ack_failed role={} messageId={} error={}", role, pahoMessageId, e.getMessage());
        }
    }

    /**
     * QoS 1 发布（阻塞直至 PUBACK）。必须在工作线程调用，禁止在 Paho 回调线程执行。
     */
    public void publish(String topic, byte[] payload, int qos) throws MqttException {
        MqttClient client = mqttClient;
        if (client == null || !client.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        client.publish(topic, message);
    }

    public void destroy() {
        cancelPendingReconnect();
        try {
            if (mqttClient != null) {
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                mqttClient.close();
            }
        } catch (MqttException e) {
            log.warn("edge_mqtt_close_failed role={} error={}", role, e.getMessage());
        } finally {
            connected.set(false);
            scheduler.shutdownNow();
        }
    }

    private MqttConnectOptions buildConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setAutomaticReconnect(false);
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
            return;
        }
        cancelPendingReconnect();
        int delay = backoffSeconds.get();
        log.warn("edge_mqtt_reconnect_scheduled role={} delaySeconds={}", role, delay);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            log.info("edge_mqtt_reconnect_attempt role={} backoffSeconds={}", role, delay);
            connect();
        }, delay, TimeUnit.SECONDS);
        pendingReconnect.set(future);
        backoffSeconds.set(Math.min(delay * 2, maxBackoffSeconds));
    }

    private void cancelPendingReconnect() {
        ScheduledFuture<?> future = pendingReconnect.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    private void subscribe() {
        if (mqttClient == null || !mqttClient.isConnected() || subscribeTopics.length == 0) {
            return;
        }
        try {
            mqttClient.subscribe(subscribeTopics, subscribeQos);
            for (int i = 0; i < subscribeTopics.length; i++) {
                log.info("edge_mqtt_subscribed role={} topic={} qos={}", role, subscribeTopics[i], subscribeQos[i]);
            }
        } catch (MqttException e) {
            log.error("edge_mqtt_subscribe_failed role={} error={}", role, e.getMessage());
        }
    }

    private class InternalCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            connected.set(true);
            backoffSeconds.set(baseBackoffSeconds);
            cancelPendingReconnect();
            log.info("edge_mqtt_connected role={} broker={} reconnect={}", role, serverURI, reconnect);
            subscribe();
        }

        @Override
        public void connectionLost(Throwable cause) {
            connected.set(false);
            log.warn("edge_mqtt_connection_lost role={} error={}", role,
                    cause == null ? "unknown" : cause.getMessage());
            scheduleReconnect();
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            MessageSink sink = messageSink;
            if (sink != null) {
                sink.handle(topic, message.getPayload(), message.getQos(), message.getId());
            } else {
                log.warn("edge_mqtt_no_sink role={} topic={} reason=handler_not_registered", role, topic);
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // 发布成功由 publish() 返回/PUBACK 决定；此处仅作回调占位
        }
    }

    /** 便捷工厂：本地与云端各一路。 */
    public static ManagedMqttClient local(EdgeProperties properties, MessageSink sink) {
        EdgeProperties.Mqtt config = properties.getLocalMqtt();
        String siteId = properties.getSiteId();
        return new ManagedMqttClient("local", config,
                new String[]{"elder/+/+/+/up/+", "edge/" + siteId + "/caregiver/+/up/receipt"},
                new int[]{1, 1}, sink);
    }

    public static ManagedMqttClient cloud(EdgeProperties properties) {
        return new ManagedMqttClient("cloud", properties.getCloudMqtt(),
                new String[0], new int[0], (topic, payload, qos, id) -> {
                });
    }
}
