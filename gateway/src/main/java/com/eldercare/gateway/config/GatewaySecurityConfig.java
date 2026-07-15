package com.eldercare.gateway.config;

import com.eldercare.common.security.config.SecurityProperties;
import com.eldercare.common.security.jwt.JwtTokenProvider;
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
     * CORS 跨域配置 — 从 yml 配置读取允许的源域名模式
     * <p>
     * 开发环境默认允许所有源（"*"），生产环境应配置为具体域名列表。
     */
    @Bean
    public CorsWebFilter corsWebFilter(SecurityProperties properties) {
        CorsConfiguration config = new CorsConfiguration();

        // 从配置读取允许的源域名模式
        SecurityProperties.CorsProperties corsProps = properties.getCors();
        if (corsProps != null && corsProps.getAllowedOriginPatterns() != null
                && !corsProps.getAllowedOriginPatterns().isEmpty()) {
            corsProps.getAllowedOriginPatterns().forEach(config::addAllowedOriginPattern);
        } else {
            // 兜底：开发环境允许所有源
            config.addAllowedOriginPattern("*");
        }

        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);

        long maxAge = corsProps != null ? corsProps.getMaxAge() : 3600L;
        config.setMaxAge(maxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
