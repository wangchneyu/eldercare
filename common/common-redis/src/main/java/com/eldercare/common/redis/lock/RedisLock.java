package com.eldercare.common.redis.lock;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的轻量级分布式锁实现。
 */
public class RedisLock {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "return redis.call('del', KEYS[1]) " +
            "else " +
            "return 0 " +
            "end",
            Long.class
    );

    public RedisLock(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取分布式锁（非阻塞模式）。
     *
     * @param lockKey    锁的 Key
     * @param requestId  请求标识（用于解锁时校验，必须全局唯一，如 UUID）
     * @param leaseTime  锁的持有时间（单位：秒），防止死锁
     * @return 是否成功获取锁
     */
    public boolean tryLock(String lockKey, String requestId, long leaseTime) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, requestId, leaseTime, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 尝试获取分布式锁（带超时重试机制，阻塞模式）。
     *
     * @param lockKey          锁的 Key
     * @param requestId        请求标识
     * @param leaseTime        锁的持有时间（单位：秒）
     * @param waitTimeSeconds  最大等待获取锁的时间（单位：秒）
     * @return 是否成功获取锁
     */
    public boolean tryLock(String lockKey, String requestId, long leaseTime, long waitTimeSeconds) {
        long expireTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(waitTimeSeconds);
        while (System.currentTimeMillis() < expireTime) {
            if (tryLock(lockKey, requestId, leaseTime)) {
                return true;
            }
            try {
                // 稍微休眠，减少 Redis 交互频率
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 释放分布式锁。
     *
     * @param lockKey   锁的 Key
     * @param requestId 请求标识（必须与加锁时一致）
     * @return 是否成功释放锁
     */
    public boolean unlock(String lockKey, String requestId) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");

        Long result = redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                requestId
        );
        return Objects.equals(result, 1L);
    }
}
