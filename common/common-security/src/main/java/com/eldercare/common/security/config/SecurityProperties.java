package com.eldercare.common.security.config;

import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.domain.UserRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全模块可配置属性
 *
 * <p>在 application.yml 中配置示例：
 * <pre>
 * eldercare:
 *   security:
 *     enabled: true
 *     secret: your-256-bit-secret-key-here-min-32-chars
 *     access-token-expiration: 7200
 *     refresh-token-expiration: 604800
 *     whitelist:
 *       - /auth/login
 *       - /auth/register
 *       - /public/**
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "eldercare.security")
public class SecurityProperties {

    /** 是否启用安全模块 */
    private boolean enabled = true;

    /**
     * JWT 签名密钥
     * <p>HS256 要求密钥长度至少 256 bits（32 字符），生产环境请使用足够强度的密钥
     */
    private String secret = "ElderCarePlatform2024SecretKey!@#$%^";

    /** 访问令牌过期时间（秒），默认 2 小时 */
    private long accessTokenExpiration = 7200;

    /** 刷新令牌过期时间（秒），默认 7 天 */
    private long refreshTokenExpiration = 604800;

    /** 鉴权白名单路径列表，支持 Ant 风格路径匹配 */
    private List<String> whitelist = new ArrayList<>();

    /** CORS 跨域配置 */
    private CorsProperties cors = new CorsProperties();

    /** RBAC 角色-路径映射列表 */
    private List<RoleMappingProperties> roleMappings = new ArrayList<>();

    {
        whitelist.addAll(List.of(SecurityConstants.DEFAULT_WHITELIST));
    }

    // ==================== 嵌套配置类 ====================

    /**
     * CORS 跨域配置属性
     */
    @Data
    public static class CorsProperties {
        /** 允许的源域名模式列表（支持 Ant 风格，如 "https://*.example.com"） */
        private List<String> allowedOriginPatterns = new ArrayList<>();
        {
            allowedOriginPatterns.add("*");
        }
        /** 预检请求缓存时间（秒） */
        private long maxAge = 3600L;
    }

    /**
     * RBAC 角色-路径映射属性
     * <p>
     * 每条映射包含一组路径模式和允许访问的角色集合。
     * 用户拥有集合中任意一个角色即可访问匹配的路径。
     */
    @Data
    public static class RoleMappingProperties {
        /** 路径模式列表，支持 Ant 风格（如 /admin/**） */
        private List<String> paths = new ArrayList<>();
        /** 允许访问的角色集合 */
        private Set<UserRole> roles = new LinkedHashSet<>();
    }
}
