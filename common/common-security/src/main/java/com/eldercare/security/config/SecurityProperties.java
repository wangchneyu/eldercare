package com.eldercare.security.config;

import com.eldercare.security.constant.SecurityConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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

    {
        whitelist.addAll(List.of(SecurityConstants.DEFAULT_WHITELIST));
    }
}
