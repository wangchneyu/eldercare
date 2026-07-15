package com.eldercare.common.redis;

import com.eldercare.common.redis.lock.RedisLock;
import com.eldercare.common.redis.service.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RedisServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RedisService redisService;
    private RedisLock redisLock;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisService = new RedisService(redisTemplate);
        redisLock = new RedisLock(redisTemplate);
    }

    @Test
    public void testRedisServiceGetAndSet() {
        // Set
        redisService.set("test_key", "test_value", 10);
        verify(valueOperations).set("test_key", "test_value", 10, TimeUnit.SECONDS);

        // Get
        when(valueOperations.get("test_key")).thenReturn("test_value");
        String result = redisService.get("test_key");
        assertEquals("test_value", result);
        verify(valueOperations).get("test_key");
    }

    @Test
    public void testRedisLockTryLockSuccess() {
        when(valueOperations.setIfAbsent(eq("lock_key"), eq("request_id"), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        boolean locked = redisLock.tryLock("lock_key", "request_id", 30L);
        assertTrue(locked);
    }

    @Test
    public void testRedisLockTryLockFailure() {
        when(valueOperations.setIfAbsent(eq("lock_key"), eq("request_id"), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        boolean locked = redisLock.tryLock("lock_key", "request_id", 30L);
        assertFalse(locked);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRedisLockUnlock() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        boolean unlocked = redisLock.unlock("lock_key", "request_id");
        assertTrue(unlocked);
    }
}
