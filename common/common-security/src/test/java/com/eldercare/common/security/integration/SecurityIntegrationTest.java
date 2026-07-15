package com.eldercare.common.security.integration;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.GlobalExceptionHandler;
import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全模块完整集成测试
 * <p>
 * 验证从 Filter → Controller → AOP 的完整安全流程
 */
@SpringBootTest(properties = {
        "eldercare.security.enabled=true",
        "eldercare.security.secret=IntegrationTestSecretKeyForJwt!2024",
        "eldercare.security.access-token-expiration=3600",
        "eldercare.security.refresh-token-expiration=86400",
        "eldercare.security.whitelist[0]=/test/public"
})
@AutoConfigureMockMvc
@Import(GlobalExceptionHandler.class)
public class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String caregiverToken;
    private String familyToken;

    @BeforeEach
    public void setUp() {
        // 预生成各角色 Token
        adminToken = jwtTokenProvider.createAccessToken(
                new LoginUser(1L, "admin", Set.of(UserRole.ADMIN)));
        caregiverToken = jwtTokenProvider.createAccessToken(
                new LoginUser(2L, "caregiver1", Set.of(UserRole.CAREGIVER)));
        familyToken = jwtTokenProvider.createAccessToken(
                new LoginUser(3L, "family1", Set.of(UserRole.FAMILY)));
    }

    // ==================== 公开接口 ====================

    @Test
    public void testPublicEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/test/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("public data"));
    }

    @Test
    public void testPublicEndpointWithToken() throws Exception {
        mockMvc.perform(get("/test/public")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("public data"));
    }

    // ==================== 仅需登录接口 ====================

    @Test
    public void testAuthOnlyEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/test/auth-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(110001));
    }

    @Test
    public void testAuthOnlyEndpointWithValidToken() throws Exception {
        mockMvc.perform(get("/test/auth-only")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("hello admin"));
    }

    // ==================== 角色权限 — ADMIN ====================

    @Test
    public void testAdminOnlyEndpointWithAdminToken() throws Exception {
        mockMvc.perform(get("/test/admin-only")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("admin resource"));
    }

    @Test
    public void testAdminOnlyEndpointWithCaregiverToken() throws Exception {
        // 看护人员无权访问管理员接口
        mockMvc.perform(get("/test/admin-only")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + caregiverToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(110002));
    }

    @Test
    public void testAdminOnlyEndpointWithFamilyToken() throws Exception {
        mockMvc.perform(get("/test/admin-only")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + familyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(110002));
    }

    // ==================== 角色权限 — CAREGIVER ====================

    @Test
    public void testCaregiverEndpointWithCaregiverToken() throws Exception {
        mockMvc.perform(get("/test/caregiver")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + caregiverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("caregiver resource"));
    }

    @Test
    public void testCaregiverEndpointWithAdminToken() throws Exception {
        // 管理员也无权访问看护人员专属接口
        mockMvc.perform(get("/test/caregiver")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(110002));
    }

    // ==================== 多角色接口 ====================

    @Test
    public void testMultiRoleEndpointWithAdminToken() throws Exception {
        mockMvc.perform(get("/test/admin-or-operator")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("admin or operator resource"));
    }

    @Test
    public void testMultiRoleEndpointWithFamilyToken() throws Exception {
        mockMvc.perform(get("/test/admin-or-operator")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + familyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(110002));
    }

    // ==================== 无效 Token ====================

    @Test
    public void testInvalidTokenFormat() throws Exception {
        mockMvc.perform(get("/test/auth-only")
                        .header(SecurityConstants.HEADER_AUTHORIZATION,
                                SecurityConstants.TOKEN_PREFIX + "invalid.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(110001));
    }

    @Test
    public void testMissingAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/test/auth-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(110001));
    }

    @Test
    public void testNoBearerPrefix() throws Exception {
        mockMvc.perform(get("/test/auth-only")
                        .header(SecurityConstants.HEADER_AUTHORIZATION, adminToken))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 验证 R 统一返回体格式 ====================

    @Test
    public void testResponseFormatConsistency() throws Exception {
        String content = mockMvc.perform(get("/test/public"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        R<?> result = objectMapper.readValue(content, R.class);
        Assertions.assertEquals(0, result.getCode());
        Assertions.assertEquals("success", result.getMsg());
        // traceId 在测试上下文中可能为空，这是预期行为
    }
}
