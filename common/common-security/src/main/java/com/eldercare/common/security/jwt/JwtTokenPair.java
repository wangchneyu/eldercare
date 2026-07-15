package com.eldercare.common.security.jwt;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * JWT Token 对：包含访问令牌和刷新令牌
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JwtTokenPair implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 访问令牌 */
    private String accessToken;

    /** 刷新令牌 */
    private String refreshToken;

    /** 访问令牌过期时间（秒） */
    private long expiresIn;

    /**
     * 创建 Token 对
     */
    public static JwtTokenPair of(String accessToken, String refreshToken, long expiresIn) {
        return new JwtTokenPair(accessToken, refreshToken, expiresIn);
    }
}
