package com.eldercare.common.redis.snowflake;

import com.eldercare.common.core.utils.IdUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的动态 Snowflake 工作机器 ID (workerId) 分配器。
 */
@Slf4j
public class RedisWorkerIdAllocator implements InitializingBean {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${spring.application.name:unknown-service}")
    private String applicationName;

    private final String instanceId = UUID.randomUUID().toString();
    private int workerId = -1;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "snowflake-heartbeat-thread");
        thread.setDaemon(true);
        return thread;
    });

    public RedisWorkerIdAllocator(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        String serviceInstanceKey = applicationName + ":" + instanceId;
        log.info("正在为当前实例注册雪花算法的工作机器 ID (workerId): {}", serviceInstanceKey);

        for (int i = 0; i < 1024; i++) {
            String key = "eldercare:snowflake:worker_id:" + i;
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, serviceInstanceKey, 60, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(success)) {
                this.workerId = i;
                log.info("成功为实例 {} 分配雪花算法工作机器 ID (workerId): {}", serviceInstanceKey, i);
                break;
            }
        }

        if (this.workerId == -1) {
            throw new IllegalStateException("分配雪花算法的工作机器 ID 失败。所有 1024 个槽位都已被占用！");
        }

        // 初始化 common-core 中的雪花算法
        IdUtil.initSnowflake(this.workerId);

        // 启动续期心跳
        startHeartbeat(serviceInstanceKey);
    }

    private void startHeartbeat(String serviceInstanceKey) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String key = "eldercare:snowflake:worker_id:" + this.workerId;
                String currentVal = (String) redisTemplate.opsForValue().get(key);
                if (serviceInstanceKey.equals(currentVal)) {
                    redisTemplate.expire(key, 60, TimeUnit.SECONDS);
                } else {
                    log.warn("雪花算法工作机器 ID 键 {} 已被覆盖或已过期。正在尝试重新夺回...", key);
                    Boolean success = redisTemplate.opsForValue().setIfAbsent(key, serviceInstanceKey, 60, TimeUnit.SECONDS);
                    if (!Boolean.TRUE.equals(success)) {
                        log.error("严重错误：重新夺回雪花算法工作机器 ID (workerId: {}) 失败。可能会产生重复的 ID！", this.workerId);
                    }
                }
            } catch (Exception e) {
                log.error("更新雪花算法工作机器 ID 的心跳续期时发生错误", e);
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        if (this.workerId != -1) {
            try {
                String key = "eldercare:snowflake:worker_id:" + this.workerId;
                String serviceInstanceKey = applicationName + ":" + instanceId;
                String currentVal = (String) redisTemplate.opsForValue().get(key);
                if (serviceInstanceKey.equals(currentVal)) {
                    redisTemplate.delete(key);
                    log.info("已成功释放实例 {} 占用的雪花算法工作机器 ID (workerId): {}", serviceInstanceKey, this.workerId);
                }
                scheduler.shutdown();
            } catch (Exception e) {
                log.warn("应用关闭时清理释放雪花算法工作机器 ID 失败", e);
            }
        }
    }
}
