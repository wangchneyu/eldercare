package com.eldercare.iot.config;

import com.eldercare.common.redis.lock.RedisLock;
import com.eldercare.common.redis.service.RedisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * IoT needs the common Redis value and lock APIs, but not the platform-wide
 * worker-ID allocator that eagerly contacts Redis during application startup.
 */
@Configuration
public class IotRedisSupportConfig {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "iotRedisTemplate")
    public RedisTemplate<String, Object> iotRedisTemplate(RedisConnectionFactory connectionFactory,
                                                           ObjectMapper objectMapper) {
        ObjectMapper redisObjectMapper = objectMapper.copy();
        redisObjectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(redisObjectMapper);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @Primary
    public RedisService redisService(RedisTemplate<String, Object> iotRedisTemplate) {
        return new RedisService(iotRedisTemplate);
    }

    @Bean
    @Primary
    public RedisLock redisLock(RedisTemplate<String, Object> iotRedisTemplate) {
        return new RedisLock(iotRedisTemplate);
    }
}
