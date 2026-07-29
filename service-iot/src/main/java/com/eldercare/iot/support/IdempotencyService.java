package com.eldercare.iot.support;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.redis.lock.RedisLock;
import com.eldercare.common.redis.service.RedisService;
import com.eldercare.iot.enums.IotErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-backed replay for write requests that provide an Idempotency-Key.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "iot:idempotency:";
    private static final String SEPARATOR = "\n";

    private final RedisService redisService;
    private final RedisLock redisLock;
    private final ObjectMapper objectMapper;

    @Value("${iot.idempotency.ttl-seconds:86400}")
    private long ttlSeconds = 86_400;

    @Value("${iot.idempotency.lock-seconds:30}")
    private long lockSeconds = 30;

    public <T> T execute(String scope, String idempotencyKey, Object request, Class<T> resultType, Supplier<T> action) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return action.get();
        }

        String cacheKey = KEY_PREFIX + scope + ":" + idempotencyKey.trim();
        String fingerprint = fingerprint(request);
        T cached = replay(cacheKey, fingerprint, resultType);
        if (cached != null) {
            return cached;
        }

        String requestId = UUID.randomUUID().toString();
        if (!redisLock.tryLock(cacheKey + ":lock", requestId, Math.max(1, lockSeconds))) {
            cached = replay(cacheKey, fingerprint, resultType);
            if (cached != null) {
                return cached;
            }
            throw new BizException(IotErrorCode.DEVICE_MESSAGE_CONFLICT);
        }

        try {
            cached = replay(cacheKey, fingerprint, resultType);
            if (cached != null) {
                return cached;
            }
            T result = action.get();
            redisService.set(cacheKey, fingerprint + SEPARATOR + serialize(result), Math.max(1, ttlSeconds));
            return result;
        } finally {
            redisLock.unlock(cacheKey + ":lock", requestId);
        }
    }

    public void executeVoid(String scope, String idempotencyKey, Object request, Runnable action) {
        execute(scope, idempotencyKey, request, VoidResult.class, () -> {
            action.run();
            return VoidResult.INSTANCE;
        });
    }

    private <T> T replay(String cacheKey, String fingerprint, Class<T> resultType) {
        String cached = redisService.get(cacheKey);
        if (!StringUtils.hasText(cached)) {
            return null;
        }
        int separator = cached.indexOf(SEPARATOR);
        if (separator < 0 || !fingerprint.equals(cached.substring(0, separator))) {
            throw new BizException(IotErrorCode.DEVICE_MESSAGE_CONFLICT);
        }
        String body = cached.substring(separator + SEPARATOR.length());
        try {
            return objectMapper.readValue(body, resultType);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to replay idempotent response", e);
        }
    }

    private String fingerprint(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(request));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint idempotent request", e);
        }
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to store idempotent response", e);
        }
    }

    private enum VoidResult {
        INSTANCE
    }
}
