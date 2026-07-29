package com.eldercare.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eldercare.iot.config.JsonbTypeHandler;
import com.eldercare.iot.entity.IotMqOutbox;
import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface IotMqOutboxMapper extends BaseMapper<IotMqOutbox> {

    /**
     * 原子插入：利用 PostgreSQL ON CONFLICT 在唯一约束冲突时返回 0，
     * 不捕获异常，不影响事务。
     *
     * @return 实际插入行数；0 表示重复
     */
    @Update("""
            INSERT INTO iot_mq_outbox (
                id, event_id, device_id, source_message_id, event_type,
                topic, tag, payload, raw_envelope, raw_envelope_json, status, retry_count,
                last_error, created_at, sent_at, next_retry_at, lease_expire_at, claimed_by
            ) VALUES (
                #{id}, #{eventId}, #{deviceId}, #{sourceMessageId}, #{eventType},
                #{topic}, #{tag},
                #{payload, typeHandler=com.eldercare.iot.config.JsonbTypeHandler},
                #{rawEnvelope, typeHandler=com.eldercare.iot.config.JsonbTypeHandler},
                #{rawEnvelopeJson}, #{status}, #{retryCount}, #{lastError}, #{createdAt}, #{sentAt},
                #{nextRetryAt}, #{leaseExpireAt}, #{claimedBy}
            )
            ON CONFLICT (device_id, source_message_id, event_type) DO NOTHING
            """)
    int insertOnConflict(IotMqOutbox outbox);

    /**
     * 条件更新状态：仅在当前状态等于 expectedStatus 时更新，避免并发下 SENT 被覆盖回 PENDING。
     *
     * @return 实际更新行数；0 表示状态已改变或记录不存在
     */
    @Update("""
            UPDATE iot_mq_outbox
            SET status = #{newStatus},
                sent_at = #{sentAt},
                lease_expire_at = NULL,
                claimed_by = NULL
            WHERE event_id = #{eventId}
              AND status = #{expectedStatus}
              AND claimed_by IS NOT DISTINCT FROM #{claimedBy}
            """)
    int updateStatusConditionally(@Param("eventId") String eventId,
                                  @Param("newStatus") String newStatus,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("sentAt") OffsetDateTime sentAt,
                                  @Param("claimedBy") String claimedBy);

    /**
     * 条件更新失败信息：仅在当前状态等于 expectedStatus 时更新 retry_count/last_error/status。
     */
    @Update("""
            UPDATE iot_mq_outbox
            SET status = #{newStatus},
                retry_count = #{retryCount},
                last_error = #{lastError},
                next_retry_at = #{nextRetryAt},
                lease_expire_at = NULL,
                claimed_by = NULL
            WHERE event_id = #{eventId}
              AND status = #{expectedStatus}
              AND claimed_by IS NOT DISTINCT FROM #{claimedBy}
            """)
    int updateFailureConditionally(@Param("eventId") String eventId,
                                   @Param("newStatus") String newStatus,
                                   @Param("expectedStatus") String expectedStatus,
                                   @Param("retryCount") int retryCount,
                                   @Param("lastError") String lastError,
                                   @Param("nextRetryAt") OffsetDateTime nextRetryAt,
                                   @Param("claimedBy") String claimedBy);

    /**
     * 原子领取 PENDING 记录：
     * 1. 用 FOR UPDATE SKIP LOCKED 锁定最老的未租约/已过期记录；
     * 2. 更新 lease_expire_at 与 claimed_by；
     * 3. RETURNING 被领取的记录。
     */
    @Select("""
            WITH claimed AS (
                SELECT id FROM iot_mq_outbox
                WHERE status = 'PENDING'
                  AND next_retry_at <= #{now}
                  AND (lease_expire_at IS NULL OR lease_expire_at < #{now})
                ORDER BY created_at
                LIMIT #{limit}
                FOR UPDATE SKIP LOCKED
            )
            UPDATE iot_mq_outbox o
            SET lease_expire_at = #{leaseExpireAt},
                claimed_by = #{claimedBy}
            FROM claimed
            WHERE o.id = claimed.id
            RETURNING o.id, o.event_id, o.device_id, o.source_message_id, o.event_type,
                      o.topic, o.tag, o.payload, o.raw_envelope, o.raw_envelope_json, o.status, o.retry_count,
                      o.last_error, o.created_at, o.sent_at, o.next_retry_at, o.lease_expire_at, o.claimed_by
            """)
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "eventId", column = "event_id"),
            @Result(property = "deviceId", column = "device_id"),
            @Result(property = "sourceMessageId", column = "source_message_id"),
            @Result(property = "eventType", column = "event_type"),
            @Result(property = "topic", column = "topic"),
            @Result(property = "tag", column = "tag"),
            @Result(property = "payload", column = "payload", typeHandler = JsonbTypeHandler.class),
            @Result(property = "rawEnvelope", column = "raw_envelope", typeHandler = JsonbTypeHandler.class),
            @Result(property = "rawEnvelopeJson", column = "raw_envelope_json"),
            @Result(property = "status", column = "status"),
            @Result(property = "retryCount", column = "retry_count"),
            @Result(property = "lastError", column = "last_error"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "sentAt", column = "sent_at"),
            @Result(property = "nextRetryAt", column = "next_retry_at"),
            @Result(property = "leaseExpireAt", column = "lease_expire_at"),
            @Result(property = "claimedBy", column = "claimed_by")
    })
    List<IotMqOutbox> claimPendingRecords(@Param("limit") int limit,
                                          @Param("now") OffsetDateTime now,
                                          @Param("leaseExpireAt") OffsetDateTime leaseExpireAt,
                                          @Param("claimedBy") String claimedBy);

    /**
     * 延长/重置租约。
     */
    @Update("""
            UPDATE iot_mq_outbox
            SET lease_expire_at = #{leaseExpireAt},
                claimed_by = #{claimedBy}
            WHERE event_id = #{eventId}
              AND status = 'PENDING'
            """)
    int updateLease(@Param("eventId") String eventId,
                    @Param("leaseExpireAt") OffsetDateTime leaseExpireAt,
                    @Param("claimedBy") String claimedBy);

    @Update("""
            UPDATE iot_mq_outbox
            SET lease_expire_at = NULL,
                claimed_by = NULL
            WHERE event_id = #{eventId}
              AND status = 'PENDING'
              AND claimed_by = #{claimedBy}
            """)
    int releaseLease(@Param("eventId") String eventId, @Param("claimedBy") String claimedBy);

    @Select("SELECT COUNT(*) FROM iot_mq_outbox WHERE status = 'PENDING'")
    long countPending();

    @Select("""
            SELECT EXTRACT(EPOCH FROM (NOW() - MIN(created_at)))
            FROM iot_mq_outbox
            WHERE status = 'PENDING'
            """)
    Long oldestPendingAgeSeconds();
}
