package com.eldercare.common.security.filter;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * JwtAuthenticationFilter 单元测试
 * <p>
 * 使用 Spring Mock 对象模拟 HTTP 请求/响应，无需启动完整 Spring 上下文
 */
public class JwtAuthenticationFilterTest {

    private static final String SECRET = "FilterTestSecretKey2024ForUnitTest!";

    private JwtTokenProvider tokenProvider;
    private ObjectMapper objectMapper;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    public void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, 3600, 86400);
        objectMapper = new ObjectMapper();
        filter = new JwtAuthenticationFilter(tokenProvider, List.of("/auth/**", "/public/**"), objectMapper);
    }

    // ==================== 白名单测试 ====================

    @Test
    public void testWhitelistLoginPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertTrue(filterCalled[0]);
        Assertions.assertEquals(200, response.getStatus());
    }

    @Test
    public void testWhitelistPublicPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertTrue(filterCalled[0]);
    }

    // ==================== 网关明文请求头直传测试 (混合模式) ====================

    @Test
    public void testGatewayHeaderAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader(SecurityConstants.HEADER_USER_ID, "100");
        request.addHeader(SecurityConstants.HEADER_USERNAME, "gateway_user");
        request.addHeader(SecurityConstants.HEADER_USER_ROLES, "ADMIN,FAMILY");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertTrue(filterCalled[0]);
        Assertions.assertEquals(200, response.getStatus());
    }

    // ==================== 缺失 Token 测试 ====================

    @Test
    public void testMissingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertFalse(filterCalled[0]);
        Assertions.assertEquals(401, response.getStatus());

        R<?> result = objectMapper.readValue(response.getContentAsString(), R.class);
        Assertions.assertEquals(110001, result.getCode());
    }

    // ==================== 有效 Token 测试 ====================

    @Test
    public void testValidToken() throws Exception {
        LoginUser user = new LoginUser(1L, "admin", Set.of(UserRole.ADMIN));
        String token = tokenProvider.createAccessToken(user);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader(SecurityConstants.HEADER_AUTHORIZATION, SecurityConstants.TOKEN_PREFIX + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertTrue(filterCalled[0]);
        Assertions.assertEquals(200, response.getStatus());
    }

    // ==================== 无效 Token 测试 ====================

    @Test
    public void testInvalidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader(SecurityConstants.HEADER_AUTHORIZATION, SecurityConstants.TOKEN_PREFIX + "invalid.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertFalse(filterCalled[0]);
        Assertions.assertEquals(401, response.getStatus());
    }

    @Test
    public void testEmptyAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader(SecurityConstants.HEADER_AUTHORIZATION, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertFalse(filterCalled[0]);
        Assertions.assertEquals(401, response.getStatus());
    }

    @Test
    public void testNoBearerPrefix() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader(SecurityConstants.HEADER_AUTHORIZATION, tokenProvider.createAccessToken(
                new LoginUser(1L, "admin", Set.of(UserRole.ADMIN))));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        filter.doFilter(request, response, chain);

        Assertions.assertFalse(filterCalled[0]);
        Assertions.assertEquals(401, response.getStatus());
    }

    // ==================== 白名单为空 ====================

    @Test
    public void testEmptyWhitelist() throws Exception {
        JwtAuthenticationFilter noWhitelistFilter = new JwtAuthenticationFilter(
                tokenProvider, Collections.emptyList(), objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] filterCalled = {false};
        FilterChain chain = (req, res) -> filterCalled[0] = true;

        noWhitelistFilter.doFilter(request, response, chain);

        Assertions.assertFalse(filterCalled[0]);
        Assertions.assertEquals(401, response.getStatus());
    }
}
