package com.eldercare.security.jwt;

import com.eldercare.security.constant.SecurityConstants;
import com.eldercare.security.domain.LoginUser;
import com.eldercare.security.domain.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * JwtTokenProvider 单元测试
 */
public class JwtTokenProviderTest {

    private static final String SECRET = "TestSecretKeyForJwtTokenProvider2024!";
    private static final long ACCESS_EXPIRATION = 3600;   // 1 小时
    private static final long REFRESH_EXPIRATION = 86400; // 1 天

    private JwtTokenProvider tokenProvider;
    private LoginUser testUser;

    @BeforeEach
    public void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);
        testUser = new LoginUser(1L, "admin", Set.of(UserRole.ADMIN));
    }

    // ==================== 签发测试 ====================

    @Test
    public void testCreateAccessToken() {
        String token = tokenProvider.createAccessToken(testUser);
        Assertions.assertNotNull(token);
        Assertions.assertTrue(token.length() > 0);

        Claims claims = tokenProvider.getClaims(token);
        Assertions.assertEquals("admin", claims.getSubject());
        Assertions.assertEquals(1L, claims.get(SecurityConstants.CLAIM_USER_ID, Long.class));
        Assertions.assertEquals(SecurityConstants.TOKEN_TYPE_ACCESS, claims.get(SecurityConstants.CLAIM_TOKEN_TYPE));
    }

    @Test
    public void testCreateRefreshToken() {
        String token = tokenProvider.createRefreshToken(testUser);
        Assertions.assertNotNull(token);

        Claims claims = tokenProvider.getClaims(token);
        Assertions.assertEquals(SecurityConstants.TOKEN_TYPE_REFRESH, claims.get(SecurityConstants.CLAIM_TOKEN_TYPE));
    }

    @Test
    public void testCreateTokenPair() {
        JwtTokenPair pair = tokenProvider.createTokenPair(testUser);
        Assertions.assertNotNull(pair);
        Assertions.assertNotNull(pair.getAccessToken());
        Assertions.assertNotNull(pair.getRefreshToken());
        Assertions.assertEquals(ACCESS_EXPIRATION, pair.getExpiresIn());
        Assertions.assertNotEquals(pair.getAccessToken(), pair.getRefreshToken());
    }

    @Test
    public void testTokenContainsRoles() {
        LoginUser multiRoleUser = new LoginUser(2L, "multiUser", Set.of(UserRole.ADMIN, UserRole.CAREGIVER));
        String token = tokenProvider.createAccessToken(multiRoleUser);

        Claims claims = tokenProvider.getClaims(token);
        String rolesStr = claims.get(SecurityConstants.CLAIM_ROLES, String.class);
        Assertions.assertTrue(rolesStr.contains("ADMIN"));
        Assertions.assertTrue(rolesStr.contains("CAREGIVER"));
    }

    // ==================== 验签测试 ====================

    @Test
    public void testValidateValidToken() {
        String token = tokenProvider.createAccessToken(testUser);
        Assertions.assertTrue(tokenProvider.validateToken(token));
    }

    @Test
    public void testValidateInvalidToken() {
        Assertions.assertFalse(tokenProvider.validateToken("invalid.token.here"));
        Assertions.assertFalse(tokenProvider.validateToken(""));
        Assertions.assertFalse(tokenProvider.validateToken(null));
    }

    @Test
    public void testValidateTamperedToken() {
        String token = tokenProvider.createAccessToken(testUser);
        // 篡改 Token：修改最后一个字符
        String tampered = token.substring(0, token.length() - 1) + "X";
        Assertions.assertFalse(tokenProvider.validateToken(tampered));
    }

    @Test
    public void testValidateTokenWithDifferentSecret() {
        String token = tokenProvider.createAccessToken(testUser);
        JwtTokenProvider otherProvider = new JwtTokenProvider("DifferentSecretKeyForTesting!2024", 3600, 86400);
        Assertions.assertFalse(otherProvider.validateToken(token));
    }

    @Test
    public void testValidateExpiredToken() {
        // 创建一个极短过期时间的 Provider
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider(SECRET, -1, -1);
        String expiredToken = shortLivedProvider.createAccessToken(testUser);
        Assertions.assertFalse(shortLivedProvider.validateToken(expiredToken));
    }

    @Test
    public void testValidateRefreshToken() {
        String refreshToken = tokenProvider.createRefreshToken(testUser);
        Assertions.assertTrue(tokenProvider.validateRefreshToken(refreshToken));

        String accessToken = tokenProvider.createAccessToken(testUser);
        Assertions.assertFalse(tokenProvider.validateRefreshToken(accessToken));
    }

    // ==================== 解析测试 ====================

    @Test
    public void testGetLoginUser() {
        String token = tokenProvider.createAccessToken(testUser);
        LoginUser parsedUser = tokenProvider.getLoginUser(token);

        Assertions.assertNotNull(parsedUser);
        Assertions.assertEquals(testUser.getUserId(), parsedUser.getUserId());
        Assertions.assertEquals(testUser.getUsername(), parsedUser.getUsername());
        Assertions.assertTrue(parsedUser.hasRole(UserRole.ADMIN));
    }

    @Test
    public void testGetLoginUserFromInvalidToken() {
        Assertions.assertNull(tokenProvider.getLoginUser("invalid"));
    }

    @Test
    public void testGetClaims() {
        String token = tokenProvider.createAccessToken(testUser);
        Claims claims = tokenProvider.getClaims(token);

        Assertions.assertNotNull(claims);
        Assertions.assertEquals("admin", claims.getSubject());
        Assertions.assertNotNull(claims.getIssuedAt());
        Assertions.assertNotNull(claims.getExpiration());
    }

    // ==================== 刷新测试 ====================

    @Test
    public void testRefreshAccessToken() {
        JwtTokenPair pair = tokenProvider.createTokenPair(testUser);
        JwtTokenPair refreshedPair = tokenProvider.refreshAccessToken(pair.getRefreshToken());

        Assertions.assertNotNull(refreshedPair);
        Assertions.assertNotNull(refreshedPair.getAccessToken());
        Assertions.assertNotNull(refreshedPair.getRefreshToken());
        Assertions.assertEquals(ACCESS_EXPIRATION, refreshedPair.getExpiresIn());
        // 刷新后的 accessToken 和 refreshToken 应该不同
        Assertions.assertNotEquals(refreshedPair.getAccessToken(), refreshedPair.getRefreshToken());
    }

    @Test
    public void testRefreshWithAccessToken() {
        String accessToken = tokenProvider.createAccessToken(testUser);
        JwtTokenPair result = tokenProvider.refreshAccessToken(accessToken);
        Assertions.assertNull(result);
    }

    @Test
    public void testRefreshWithInvalidToken() {
        Assertions.assertNull(tokenProvider.refreshAccessToken("invalid"));
    }

    // ==================== 空角色用户 ====================

    @Test
    public void testUserWithEmptyRoles() {
        LoginUser noRoleUser = new LoginUser(3L, "guest", Set.of());
        String token = tokenProvider.createAccessToken(noRoleUser);

        LoginUser parsed = tokenProvider.getLoginUser(token);
        Assertions.assertNotNull(parsed);
        Assertions.assertTrue(parsed.getRoles().isEmpty());
    }
}
