package com.eldercare.gateway.config;

import com.eldercare.security.config.SecurityProperties;
import com.eldercare.security.jwt.JwtTokenProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Gateway 安全配置
 * <p>
 * 仅创建 JwtTokenProvider Bean（复用 common-security 的纯 POJO）和 CORS 过滤器，
 * 不触发 SecurityAutoConfiguration 中的 Servlet Filter 注册。
 * <p>
 * 构造参数与 SecurityAutoConfiguration 中完全一致，确保行为统一。
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class GatewaySecurityConfig {

    /**
     * 直接复用 common-security 的 JwtTokenProvider。
     * 该类是纯 POJO，无 Spring/Servlet 依赖，可在 WebFlux Gateway 中安全使用。
     */
    @Bean
    public JwtTokenProvider jwtTokenProvider(SecurityProperties properties) {
        return new JwtTokenProvider(
                properties.getSecret(),
                properties.getAccessTokenExpiration(),
                properties.getRefreshTokenExpiration()
        );
    }

    /**
     * CORS 跨域配置 — 允许四端（服务端/管理端/老人端/亲属端）跨域访问
     * <p>
     * 生产环境应将 allowedOriginPattern 限制为具体域名
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
