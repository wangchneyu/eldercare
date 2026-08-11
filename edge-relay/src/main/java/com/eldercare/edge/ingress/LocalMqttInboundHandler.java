package com.eldercare.edge.ingress;

import com.eldercare.edge.config.EdgeProperties;
import com.eldercare.edge.metrics.EdgeMetrics;
import com.eldercare.edge.model.EnvelopeFields;
import com.eldercare.edge.model.InboundDelivery;
import com.eldercare.edge.mqtt.ManagedMqttClient;
import com.eldercare.edge.notify.ReceiptProcessor;
import com.eldercare.edge.validation.IngressValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 本地 MQTT 入站入口（Paho 回调 → 有界执行器）。
 * <p>
 * 回调线程只做路由与投递；阻塞 SQLite、通知发布、ACK 全部发生在 edge-ingress-* 工作线程。
 * 有界队列满时拒绝投递且不 ACK，Broker 持久会话会在重连后重发。
 */
@Component
public class LocalMqttInboundHandler implements ManagedMqttClient.MessageSink {

    private static final Logger log = LoggerFactory.getLogger(LocalMqttInboundHandler.class);

    private final EdgeProperties properties;
    private final IngressValidator validator;
    private final IngressPersistService persistService;
    private final ReceiptProcessor receiptProcessor;
    private final ManagedMqttClient localClient;
    private final EdgeMetrics metrics;
    private final ThreadPoolExecutor ingressExecutor;

    public LocalMqttInboundHandler(EdgeProperties properties, IngressValidator validator,
                                   IngressPersistService persistService,
                                   ReceiptProcessor receiptProcessor,
                                   @Qualifier("edgeLocalMqttClient") ManagedMqttClient localClient,
                                   EdgeMetrics metrics,
                                   ThreadPoolExecutor ingressExecutor) {
        this.properties = properties;
        this.validator = validator;
        this.persistService = persistService;
        this.receiptProcessor = receiptProcessor;
        this.localClient = localClient;
        this.metrics = metrics;
        this.ingressExecutor = ingressExecutor;
    }

    @PostConstruct
    void registerSelfAsLocalMqttSink() {
        localClient.setMessageSink(this);
    }

    @Override
    public void handle(String topic, byte[] payload, int qos, int pahoMessageId) {
        InboundDelivery delivery = new InboundDelivery(topic, payload, qos, pahoMessageId);
        try {
            ingressExecutor.execute(() -> dispatch(delivery));
        } catch (RejectedExecutionException e) {
            log.warn("edge_ingress_backpressure topic={} reason=queue_full_no_ack_wait_redelivery", topic);
        }
    }

    private void dispatch(InboundDelivery delivery) {
        String siteId = properties.getSiteId();
        if (isReceiptTopic(delivery.topic(), siteId)) {
            boolean ack = receiptProcessor.process(delivery, siteId);
            if (ack) {
                ackIfQos1(delivery);
            }
            return;
        }
        Optional<EnvelopeFields> envelope =
                validator.validateDeviceUplink(delivery.topic(), delivery.payload());
        if (envelope.isEmpty()) {
            metrics.rejected(reasonOf(delivery.topic()), siteId);
            ackIfQos1(delivery);
            return;
        }
        boolean ack = persistService.processDeviceUplink(delivery, envelope.get());
        if (ack) {
            ackIfQos1(delivery);
        }
    }

    private boolean isReceiptTopic(String topic, String siteId) {
        String[] segments = topic.split("/", -1);
        return segments.length == 6
                && "edge".equals(segments[0])
                && siteId.equals(segments[1])
                && "caregiver".equals(segments[2])
                && !segments[3].isBlank()
                && "up".equals(segments[4])
                && "receipt".equals(segments[5]);
    }

    private String reasonOf(String topic) {
        if (topic.startsWith("edge/")) {
            return "receipt_or_unknown_edge_topic";
        }
        return "device_uplink_invalid";
    }

    private void ackIfQos1(InboundDelivery delivery) {
        if (delivery.qos() == 1) {
            localClient.ack(delivery.pahoMessageId(), 1);
            metrics.acked(properties.getSiteId());
        }
    }
}
