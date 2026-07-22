package com.eldercare.common.security.jwt;

import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JWT Token 提供者：签发、验签、刷新、提取用户信息
 * <p>
 * 使用 HMAC-SHA256 签名算法，基于 jjwt 0.11.5 API
 */
@Slf4j
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    /**
     * @param secret                  签名密钥（明文，实际使用时会转为 HMAC-SHA256 密钥，最小 32 字符）
     * @param accessTokenExpiration   访问令牌过期时间（秒）
     * @param refreshTokenExpiration  刷新令牌过期时间（秒）
     * @throws IllegalStateException 密钥为空或长度不足 32 字符时抛出
     */
    public JwtTokenProvider(String secret, long accessTokenExpiration, long refreshTokenExpiration) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足，最小 32 字符（HS256 要求）。请通过环境变量 ELDERCARE_JWT_SECRET 或配置 eldercare.security.secret 设置");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    // ==================== 签发 ====================

    /**
     * 签发访问令牌
     *
     * @param loginUser 登录用户信息
     * @return JWT Token 字符串
     */
    public String createAccessToken(LoginUser loginUser) {
        return buildToken(loginUser, accessTokenExpiration, SecurityConstants.TOKEN_TYPE_ACCESS);
    }

    /**
     * 签发刷新令牌
     *
     * @param loginUser 登录用户信息
     * @return JWT Token 字符串
     */
    public String createRefreshToken(LoginUser loginUser) {
        return buildToken(loginUser, refreshTokenExpiration, SecurityConstants.TOKEN_TYPE_REFRESH);
    }

    /**
     * 签发 Token 对（access + refresh）
     */
    public JwtTokenPair createTokenPair(LoginUser loginUser) {
        String accessToken = createAccessToken(loginUser);
        String refreshToken = createRefreshToken(loginUser);
        return JwtTokenPair.of(accessToken, refreshToken, accessTokenExpiration);
    }

    // ==================== 验签 ====================

    /**
     * 校验 Token 是否有效
     *
     * @param token JWT Token
     * @return true 有效，false 无效
     */
    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("Token 已过期: {}", e.getMessage());
            return false;
        } catch (JwtException e) {
            log.debug("Token 无效: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 校验刷新令牌是否有效
     */
    public boolean validateRefreshToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return SecurityConstants.TOKEN_TYPE_REFRESH.equals(claims.get(SecurityConstants.CLAIM_TOKEN_TYPE));
        } catch (JwtException e) {
            log.debug("刷新令牌无效: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 解析 ====================

    /**
     * 从 Token 中提取用户信息
     *
     * @param token JWT Token
     * @return LoginUser，解析失败返回 null
     */
    public LoginUser getLoginUser(String token) {
        try {
            Claims claims = parseClaims(token);
            Long userId = claims.get(SecurityConstants.CLAIM_USER_ID, Long.class);
            String username = claims.get(SecurityConstants.CLAIM_USERNAME, String.class);
            String rolesStr = claims.get(SecurityConstants.CLAIM_ROLES, String.class);

            Set<UserRole> roles = parseRoles(rolesStr);
            return new LoginUser(userId, username, roles);
        } catch (JwtException e) {
            log.debug("从 Token 解析用户信息失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 Token 中的所有 Claims
     */
    public Claims getClaims(String token) {
        return parseClaims(token);
    }

    // ==================== 刷新 ====================

    /**
     * 使用刷新令牌刷新访问令牌
     *
     * @param refreshToken 有效的刷新令牌
     * @return 新的 Token 对，刷新令牌无效时返回 null
     */
    public JwtTokenPair refreshAccessToken(String refreshToken) {
        if (!validateRefreshToken(refreshToken)) {
            return null;
        }
        LoginUser loginUser = getLoginUser(refreshToken);
        if (loginUser == null) {
            return null;
        }
        return createTokenPair(loginUser);
    }

    // ==================== 获取配置 ====================

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    // ==================== 内部方法 ====================

    /**
     * 构建 JWT Token
     */
    private String buildToken(LoginUser loginUser, long expiration, String tokenType) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration * 1000);

        String rolesStr = loginUser.getRoles() != null
                ? loginUser.getRoles().stream().map(Enum::name).collect(Collectors.joining(","))
                : "";

        return Jwts.builder()
                .setId(UUID.randomUUID().toString().replace("-", ""))
                .setSubject(loginUser.getUsername())
                .claim(SecurityConstants.CLAIM_USER_ID, loginUser.getUserId())
                .claim(SecurityConstants.CLAIM_USERNAME, loginUser.getUsername())
                .claim(SecurityConstants.CLAIM_ROLES, rolesStr)
                .claim(SecurityConstants.CLAIM_TOKEN_TYPE, tokenType)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 Token Claims
     */
    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 解析角色字符串为角色枚举集合
     */
    private Set<UserRole> parseRoles(String rolesStr) {
        if (rolesStr == null || rolesStr.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(rolesStr.split(","))
                .map(String::trim)
                .map(UserRole::valueOf)
                .collect(Collectors.toSet());
    }
}
