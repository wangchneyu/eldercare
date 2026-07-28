package com.eldercare.iot.mq;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.iot.entity.IotVitalDeliveryOutbox;
import com.eldercare.iot.mapper.IotVitalDeliveryOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Low-priority durable C04 delivery compensation with an atomic lease. */
@Slf4j
@Component
public class VitalDeliveryRetryTask {

    static final String TRACE_ID_HEADER = "X-Trace-Id";
    static final String ORIGINAL_TOPIC_HEADER = "X-Original-Topic";
    static final String ORIGINAL_TAG_HEADER = "X-Original-Tag";

    private final IotVitalDeliveryOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final IotMetrics metrics;
    private final ScheduledExecutorService scheduler;

    @Value("${iot.vital-delivery.retry.batch-size:100}")
    private int batchSize = 100;

    @Value("${iot.vital-delivery.retry.lease-minutes:5}")
    private int leaseMinutes = 5;

    @Value("${iot.vital-delivery.retry.fixed-delay-ms:30000}")
    private long fixedDelayMs = 30_000L;

    @Value("${iot.vital-delivery.retry.backoff-seconds:300}")
    private long retryBackoffSeconds = 300L;

    @Value("${iot.vital-delivery.terminal-retention-hours:168}")
    private long terminalRetentionHours = 168L;

    @Value("${iot.vital-delivery.cleanup.fixed-delay-ms:3600000}")
    private long cleanupFixedDelayMs = 3_600_000L;

    private String instanceId;
    private OffsetDateTime nextCleanupAt;

    public VitalDeliveryRetryTask(IotVitalDeliveryOutboxMapper outboxMapper,
                                  RocketMQTemplate rocketMQTemplate,
                                  IotMetrics metrics,
                                  @Qualifier("iotVitalDeliveryRetryScheduler") ScheduledExecutorService scheduler) {
        this.outboxMapper = outboxMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void init() {
        instanceId = resolveInstanceId();
        scheduler.scheduleWithFixedDelay(this::retryPending, fixedDelayMs, fixedDelayMs, TimeUnit.MILLISECONDS);
    }

    void retryPending() {
        refreshMetrics();
        OffsetDateTime now = OffsetDateTime.now();
        cleanupTerminalRecords(now);
        List<IotVitalDeliveryOutbox> claimed;
        try {
            claimed = outboxMapper.claimPendingRecords(
                    batchSize, now, now.plusMinutes(leaseMinutes), currentInstanceId());
        } catch (Exception e) {
            log.error("C04 delivery claim failed", e);
            return;
        }

        for (IotVitalDeliveryOutbox outbox : claimed) {
            if (outbox.getRawEnvelopeJson() == null || outbox.getRawEnvelopeJson().isBlank()) {
                recordFailure(outbox, new IllegalStateException("rawEnvelopeJson is empty"));
            } else if (outbox.getExpiresAt() != null && !now.isBefore(outbox.getExpiresAt())) {
                publishToIsolationTopic(outbox);
            } else {
                resendOriginal(outbox);
            }
        }
    }

    private void resendOriginal(IotVitalDeliveryOutbox outbox) {
        send(outbox.getTopic() + ":" + outbox.getTag(), outbox, () -> {
            int rows = outboxMapper.markSent(outbox.getEventId(), currentInstanceId(), OffsetDateTime.now());
            if (rows > 0) {
                metrics.mqSent(outbox.getTopic(), outbox.getTag());
                log.info("C04 durable retry succeeded: eventId={}", outbox.getEventId());
            }
        });
    }

    private void publishToIsolationTopic(IotVitalDeliveryOutbox outbox) {
        String destination = MqTopicConstants.VITAL_DELIVERY_FAILED_TOPIC + ":" + outbox.getTag();
        send(destination, outbox, () -> {
            int rows = outboxMapper.markQuarantined(outbox.getEventId(), currentInstanceId(), OffsetDateTime.now());
            if (rows > 0) {
                metrics.vitalDeliveryQuarantined(outbox.getDeviceType());
                log.error("C04 delivery window expired; original envelope quarantined: eventId={}", outbox.getEventId());
            }
        });
    }

    private void send(String destination, IotVitalDeliveryOutbox outbox, Runnable onSuccess) {
        Message<String> message = MessageBuilder.withPayload(outbox.getRawEnvelopeJson())
                .setHeader(TRACE_ID_HEADER, outbox.getTraceId())
                .setHeader(ORIGINAL_TOPIC_HEADER, outbox.getTopic())
                .setHeader(ORIGINAL_TAG_HEADER, outbox.getTag())
                .build();
        try {
            rocketMQTemplate.asyncSend(destination, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    withTraceId(outbox.getTraceId(), () -> {
                        if (sendResult != null && SendStatus.SEND_OK == sendResult.getSendStatus()) {
                            onSuccess.run();
                        } else {
                            recordFailure(outbox, new IllegalStateException("RocketMQ did not return SEND_OK"));
                        }
                    });
                }

                @Override
                public void onException(Throwable cause) {
                    withTraceId(outbox.getTraceId(), () -> recordFailure(outbox, cause));
                }
            }, 3_000L);
        } catch (Exception cause) {
            recordFailure(outbox, cause);
        }
    }

    private void recordFailure(IotVitalDeliveryOutbox outbox, Throwable cause) {
        int retryCount = outbox.getRetryCount() == null ? 1 : outbox.getRetryCount() + 1;
        try {
            int rows = outboxMapper.recordFailure(
                    outbox.getEventId(),
                    currentInstanceId(),
                    retryCount,
                    VitalDeliveryOutboxService.errorMessage(cause),
                    OffsetDateTime.now().plusSeconds(retryBackoffSeconds)
            );
            if (rows > 0) {
                metrics.vitalDeliveryRetried(outbox.getDeviceType());
            }
        } catch (Exception e) {
            log.error("C04 delivery failure update failed: eventId={}", outbox.getEventId(), e);
            releaseLease(outbox);
        }
    }

    private void releaseLease(IotVitalDeliveryOutbox outbox) {
        try {
            outboxMapper.releaseLease(outbox.getEventId(), currentInstanceId());
        } catch (Exception e) {
            log.error("C04 delivery lease release failed: eventId={}", outbox.getEventId(), e);
        }
    }

    private void refreshMetrics() {
        try {
            metrics.updateVitalDeliverySnapshot(outboxMapper.countPending(), outboxMapper.oldestPendingAgeSeconds());
        } catch (Exception e) {
            log.warn("C04 delivery metrics refresh failed: reason={}", e.getMessage());
        }
    }

    private void cleanupTerminalRecords(OffsetDateTime now) {
        if (nextCleanupAt != null && now.isBefore(nextCleanupAt)) {
            return;
        }
        nextCleanupAt = now.plusNanos(TimeUnit.MILLISECONDS.toNanos(cleanupFixedDelayMs));
        try {
            int deleted = outboxMapper.deleteTerminalBefore(now.minusHours(terminalRetentionHours));
            if (deleted > 0) {
                log.info("C04 delivery terminal records cleaned: count={}", deleted);
            }
        } catch (Exception e) {
            log.warn("C04 delivery terminal cleanup failed: reason={}", e.getMessage());
        }
    }

    private String currentInstanceId() {
        if (instanceId == null) {
            instanceId = resolveInstanceId();
        }
        return instanceId;
    }

    private static String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private static void withTraceId(String traceId, Runnable action) {
        String previousTraceId = TraceContext.currentTraceId();
        try {
            TraceContext.setTraceId(traceId);
            action.run();
        } finally {
            if (previousTraceId == null) {
                TraceContext.clear();
            } else {
                TraceContext.setTraceId(previousTraceId);
            }
        }
    }
}
