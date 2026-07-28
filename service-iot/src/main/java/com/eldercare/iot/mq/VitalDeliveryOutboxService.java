package com.eldercare.iot.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eldercare.iot.entity.IotVitalDeliveryOutbox;
import com.eldercare.iot.enums.VitalDeliveryStatus;
import com.eldercare.iot.mapper.IotVitalDeliveryOutboxMapper;
import com.eldercare.iot.metrics.IotMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/** Persists C04 only after all foreground RocketMQ retries have failed. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VitalDeliveryOutboxService {

    private static final int MAX_ERROR_LENGTH = 1024;

    private final IotVitalDeliveryOutboxMapper outboxMapper;
    private final IotMetrics metrics;

    @Value("${iot.vital-delivery.max-age-hours:24}")
    private long maxAgeHours = 24;

    public void captureFinalFailure(VitalDeliveryMessage message, int retryCount, Throwable cause) {
        OffsetDateTime now = OffsetDateTime.now();
        IotVitalDeliveryOutbox outbox = new IotVitalDeliveryOutbox();
        outbox.setId(IdWorker.getId());
        outbox.setEventId(message.eventId());
        outbox.setDeviceId(message.deviceId());
        outbox.setSourceMessageId(message.sourceMessageId());
        outbox.setDeviceType(message.deviceType());
        outbox.setTopic(MqTopicConstants.VITAL_SIGN_TOPIC);
        outbox.setTag(message.deviceType());
        outbox.setTraceId(message.traceId());
        outbox.setRawEnvelopeJson(message.rawEnvelopeJson());
        outbox.setStatus(VitalDeliveryStatus.PENDING.getCode());
        outbox.setRetryCount(retryCount);
        outbox.setLastError(errorMessage(cause));
        outbox.setCreatedAt(now);
        outbox.setNextRetryAt(now);
        outbox.setExpiresAt(now.plusHours(maxAgeHours));

        int rows = outboxMapper.insertOnConflict(outbox);
        if (rows > 0) {
            metrics.vitalDeliverySaved(message.deviceType());
            log.error("C04 foreground retries exhausted; stored failure-only delivery record: eventId={}, expiresAt={}",
                    message.eventId(), outbox.getExpiresAt());
        } else {
            metrics.vitalDeliveryDuplicate(message.deviceType());
            log.warn("C04 failure-only delivery record already exists: eventId={}", message.eventId());
        }
    }

    static String errorMessage(Throwable cause) {
        if (cause == null) {
            return "RocketMQ send did not return SEND_OK";
        }
        String message = cause.getMessage();
        String text = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
