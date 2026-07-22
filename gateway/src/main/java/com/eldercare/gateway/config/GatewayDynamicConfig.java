package com.eldercare.gateway.config;

import com.eldercare.common.security.config.SecurityProperties;
import com.eldercare.common.security.domain.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 网关动态配置刷新
 * <p>
 * 通过 Spring Cloud Nacos Config 的 @RefreshScope 机制，当 Nacos 配置中心的
 * {@code eldercare.security.role-mappings} 或 {@code eldercare.security.whitelist} 发生变更时，
 * SecurityProperties Bean 会被重新绑定，RbacAuthGlobalFilter 和 JwtAuthGlobalFilter
 * 读取到最新的配置值，无需重启网关。
 * <p>
 * 使用方式：在 Nacos 配置中心的 service-gateway.yaml 中维护以下配置项：
 * <pre>
 * eldercare:
 *   security:
 *     whitelist:
 *       - /auth/login
 *       - /auth/register
 *       - /actuator/health
 *     role-mappings:
 *       - paths:
 *           - /admin/**
 *         roles:
 *           - ADMIN
 *           - OPERATOR
 * </pre>
 * <p>
 * 注：@RefreshScope 使得 Nacos 配置变更后 Spring 自动重新创建 Bean，
 * 各 Filter 通过注入 SecurityProperties 即可读取最新值。
 */
@Slf4j
@Configuration
@RefreshScope
public class GatewayDynamicConfig {

    private final SecurityProperties securityProperties;

    public GatewayDynamicConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
        log.info("网关动态配置初始化完成: whitelist={}条, roleMappings={}条",
                securityProperties.getWhitelist() != null ? securityProperties.getWhitelist().size() : 0,
                securityProperties.getRoleMappings() != null ? securityProperties.getRoleMappings().size() : 0);
    }
}
