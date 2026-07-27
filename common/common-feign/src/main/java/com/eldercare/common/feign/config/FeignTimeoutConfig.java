package com.eldercare.common.feign.config;

import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * Feign 默认超时与重试配置
 * <p>
 * 所有 @FeignClient 自动继承此默认配置（除非显式指定 configuration）。
 * <p>
 * 可在各服务 application.yml 中按需覆盖:
 * <pre>{@code
 * feign:
 *   client:
 *     config:
 *       default:
 *         connectTimeout: 5000
 *         readTimeout: 10000
 * }</pre>
 */
@AutoConfiguration
public class FeignTimeoutConfig {

    @Value("${feign.client.config.default.connectTimeout:5000}")
    private int connectTimeout;

    @Value("${feign.client.config.default.readTimeout:10000}")
    private int readTimeout;

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(connectTimeout, TimeUnit.MILLISECONDS,
                readTimeout, TimeUnit.MILLISECONDS, true);
    }

    /**
     * Feign 默认不重试（重试由 Sentinel 熔断/Sentinel 控制，不做 Feign 层重试避免雪崩）
     */
    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
