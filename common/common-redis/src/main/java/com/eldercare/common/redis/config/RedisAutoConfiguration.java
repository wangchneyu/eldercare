package com.eldercare.common.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;

/**
 * Redis 公共自动配置类。
 */
@AutoConfiguration
@EnableCaching
public class RedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 使用 GenericJackson2JsonRedisSerializer，并配置 ObjectMapper 支持 Java8 时间类型
        GenericJackson2JsonRedisSerializer jacksonSerializer = new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        // Key/HashKey 采用 String 序列化
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());

        // Value/HashValue 采用 JSON 序列化
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnMissingBean(RedisCacheManager.class)
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 默认缓存配置：TTL 为 3600 秒（1小时），不缓存空值
        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(3600))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(redisObjectMapper())))
                .disableCachingNullValues();

        return new TtlRedisCacheManager(RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory), defaultCacheConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.eldercare.common.redis.service.RedisService redisService(RedisTemplate<String, Object> redisTemplate) {
        return new com.eldercare.common.redis.service.RedisService(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.eldercare.common.redis.lock.RedisLock redisLock(RedisTemplate<String, Object> redisTemplate) {
        return new com.eldercare.common.redis.lock.RedisLock(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.eldercare.common.redis.snowflake.RedisWorkerIdAllocator redisWorkerIdAllocator(RedisTemplate<String, Object> redisTemplate) {
        return new com.eldercare.common.redis.snowflake.RedisWorkerIdAllocator(redisTemplate);
    }

    /**
     * 构建用于 Redis 序列化的 ObjectMapper，保障 Java8 状态类型的反序列化和类型安全。
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 激活默认类型，反序列化时带上具体类型
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        // 注册时间模块以支持 java.time 包下的所有类型（如 LocalDateTime 等）
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 自定义 RedisCacheManager，支持通过 `cacheName#ttl` (秒) 解析动态过期时间。
     */
    private static class TtlRedisCacheManager extends RedisCacheManager {
        public TtlRedisCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration) {
            super(cacheWriter, defaultCacheConfiguration);
        }

        @Override
        protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfig) {
            if (name != null && name.contains("#")) {
                String[] array = name.split("#");
                name = array[0];
                try {
                    long ttl = Long.parseLong(array[1]);
                    cacheConfig = cacheConfig.entryTtl(Duration.ofSeconds(ttl));
                } catch (NumberFormatException ignored) {
                    // 如果解析失败，采用默认过期时间
                }
            }
            return super.createRedisCache(name, cacheConfig);
        }
    }
}
