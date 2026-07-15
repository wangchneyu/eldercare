package com.eldercare.common.redis;

import com.eldercare.common.core.utils.IdUtil;
import com.eldercare.common.redis.snowflake.RedisWorkerIdAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class RedisWorkerIdAllocatorTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    public void testWorkerIdAllocationSuccess() throws Exception {
        // 模拟前 5 个 slot 被占用了，第 5 个 slot 成功抢到
        when(valueOperations.setIfAbsent(eq("eldercare:snowflake:worker_id:0"), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(valueOperations.setIfAbsent(eq("eldercare:snowflake:worker_id:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(valueOperations.setIfAbsent(eq("eldercare:snowflake:worker_id:2"), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(valueOperations.setIfAbsent(eq("eldercare:snowflake:worker_id:3"), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(valueOperations.setIfAbsent(eq("eldercare:snowflake:worker_id:4"), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(valueOperations.setIfAbsent(eq("eldercare:snowflake:worker_id:5"), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);

        RedisWorkerIdAllocator allocator = new RedisWorkerIdAllocator(redisTemplate);
        ReflectionTestUtils.setField(allocator, "applicationName", "test-service");

        allocator.afterPropertiesSet();

        // 验证获得的 workerId 是 5
        int workerId = (int) ReflectionTestUtils.getField(allocator, "workerId");
        assertEquals(5, workerId);

        // 验证 IdUtil 的生成器已经工作且生成的 id 大于 0
        Long nextId = IdUtil.nextId();
        assertNotNull(nextId);
        assertTrue(nextId > 0);

        allocator.destroy();
    }
}
