package com.eldercare.common.security.config;

import com.eldercare.common.security.aop.RequireRoleAspect;
import com.eldercare.common.security.feign.FeignAuthRequestInterceptor;
import com.eldercare.common.security.filter.JwtAuthenticationFilter;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全模块自动配置
 * <p>
 * 通过 SPI 机制自动注册，可通过 {@code eldercare.security.enabled=false} 关闭
 */
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
@ConditionalOnProperty(prefix = "eldercare.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication
public class SecurityAutoConfiguration {

    // ==================== JWT ====================

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(SecurityProperties properties) {
        return new JwtTokenProvider(
                properties.getSecret(),
                properties.getAccessTokenExpiration(),
                properties.getRefreshTokenExpiration()
        );
    }

    // ==================== Filter ====================

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtTokenProvider jwtTokenProvider,
            SecurityProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider, properties.getWhitelist(), objectMapper);

        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setOrder(-100);           // 高优先级
        registration.addUrlPatterns("/*");      // 拦截所有请求
        registration.setName("jwtAuthenticationFilter");
        return registration;
    }

    // ==================== AOP ====================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    public RequireRoleAspect requireRoleAspect() {
        return new RequireRoleAspect();
    }

    // ==================== Feign ====================

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    public static class FeignClientConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public FeignAuthRequestInterceptor feignAuthRequestInterceptor() {
            return new FeignAuthRequestInterceptor();
        }
    }
}
