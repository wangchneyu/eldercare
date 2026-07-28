package com.eldercare.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eldercare.iot.entity.IotVitalDeliveryOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface IotVitalDeliveryOutboxMapper extends BaseMapper<IotVitalDeliveryOutbox> {

    @Update("""
            INSERT INTO iot_vital_delivery_outbox (
                id, event_id, device_id, source_message_id, device_type, topic, tag, trace_id,
                raw_envelope_json, status, retry_count, last_error, created_at, next_retry_at,
                expires_at, sent_at, quarantined_at, lease_expire_at, claimed_by
            ) VALUES (
                #{id}, #{eventId}, #{deviceId}, #{sourceMessageId}, #{deviceType}, #{topic}, #{tag}, #{traceId},
                #{rawEnvelopeJson}, #{status}, #{retryCount}, #{lastError}, #{createdAt}, #{nextRetryAt},
                #{expiresAt}, #{sentAt}, #{quarantinedAt}, #{leaseExpireAt}, #{claimedBy}
            )
            ON CONFLICT (event_id) DO NOTHING
            """)
    int insertOnConflict(IotVitalDeliveryOutbox outbox);

    @Select("""
            WITH claimed AS (
                SELECT id FROM iot_vital_delivery_outbox
                WHERE status = 'PENDING'
                  AND next_retry_at <= #{now}
                  AND (lease_expire_at IS NULL OR lease_expire_at < #{now})
                ORDER BY created_at
                LIMIT #{limit}
                FOR UPDATE SKIP LOCKED
            )
            UPDATE iot_vital_delivery_outbox o
            SET lease_expire_at = #{leaseExpireAt},
                claimed_by = #{claimedBy}
            FROM claimed
            WHERE o.id = claimed.id
            RETURNING o.id, o.event_id, o.device_id, o.source_message_id, o.device_type,
                      o.topic, o.tag, o.trace_id, o.raw_envelope_json, o.status, o.retry_count,
                      o.last_error, o.created_at, o.next_retry_at, o.expires_at, o.sent_at,
                      o.quarantined_at, o.lease_expire_at, o.claimed_by
            """)
    List<IotVitalDeliveryOutbox> claimPendingRecords(@Param("limit") int limit,
                                                     @Param("now") OffsetDateTime now,
                                                     @Param("leaseExpireAt") OffsetDateTime leaseExpireAt,
                                                     @Param("claimedBy") String claimedBy);

    @Update("""
            UPDATE iot_vital_delivery_outbox
            SET status = 'SENT',
                sent_at = #{sentAt},
                lease_expire_at = NULL,
                claimed_by = NULL
            WHERE event_id = #{eventId}
              AND status = 'PENDING'
              AND claimed_by = #{claimedBy}
            """)
    int markSent(@Param("eventId") String eventId,
                 @Param("claimedBy") String claimedBy,
                 @Param("sentAt") OffsetDateTime sentAt);

    @Update("""
            UPDATE iot_vital_delivery_outbox
            SET retry_count = #{retryCount},
                last_error = #{lastError},
                next_retry_at = #{nextRetryAt},
                lease_expire_at = NULL,
                claimed_by = NULL
            WHERE event_id = #{eventId}
              AND status = 'PENDING'
              AND claimed_by = #{claimedBy}
            """)
    int recordFailure(@Param("eventId") String eventId,
                      @Param("claimedBy") String claimedBy,
                      @Param("retryCount") int retryCount,
                      @Param("lastError") String lastError,
                      @Param("nextRetryAt") OffsetDateTime nextRetryAt);

    @Update("""
            UPDATE iot_vital_delivery_outbox
            SET status = 'QUARANTINED',
                quarantined_at = #{quarantinedAt},
                lease_expire_at = NULL,
                claimed_by = NULL
            WHERE event_id = #{eventId}
              AND status = 'PENDING'
              AND claimed_by = #{claimedBy}
            """)
    int markQuarantined(@Param("eventId") String eventId,
                        @Param("claimedBy") String claimedBy,
                        @Param("quarantinedAt") OffsetDateTime quarantinedAt);

    @Update("""
            UPDATE iot_vital_delivery_outbox
            SET lease_expire_at = NULL,
                claimed_by = NULL
            WHERE event_id = #{eventId}
              AND status = 'PENDING'
              AND claimed_by = #{claimedBy}
            """)
    int releaseLease(@Param("eventId") String eventId, @Param("claimedBy") String claimedBy);

    @Select("SELECT COUNT(*) FROM iot_vital_delivery_outbox WHERE status = 'PENDING'")
    long countPending();

    @Select("""
            SELECT EXTRACT(EPOCH FROM (NOW() - MIN(created_at)))
            FROM iot_vital_delivery_outbox
            WHERE status = 'PENDING'
            """)
    Long oldestPendingAgeSeconds();

    @Delete("""
            DELETE FROM iot_vital_delivery_outbox
            WHERE status IN ('SENT', 'QUARANTINED')
              AND COALESCE(sent_at, quarantined_at) < #{before}
            """)
    int deleteTerminalBefore(@Param("before") OffsetDateTime before);
}
