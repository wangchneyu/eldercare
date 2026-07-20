package com.eldercare.gateway.filter;

import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.gateway.GatewayApplication;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 角色-路径拦截集成测试
 * <p>
 * 验证 RbacAuthGlobalFilter 的完整行为:
 * <ol>
 *   <li>低权限角色访问高权限路径 → 403</li>
 *   <li>高权限角色（ADMIN）访问任意路径 → 200</li>
 *   <li>匹配角色访问对应路径 → 200</li>
 *   <li>无角色用户访问受保护路径 → 403</li>
 * </ol>
 * <p>
 * 角色-路径映射（与 application.yml 一致）:
 * <ul>
 *   <li>/admin/** → ADMIN, OPERATOR</li>
 *   <li>/server/** → ADMIN, OPERATOR, CAREGIVER</li>
 *   <li>/elderly/** → ADMIN, CAREGIVER</li>
 *   <li>/family/** → ADMIN, FAMILY</li>
 * </ul>
 */
@SpringBootTest(classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "eldercare.security.enabled=true",
                "eldercare.security.secret=GatewayTestSecretKey2024!@#$%^&*(256bit)"
        })
@AutoConfigureWebTestClient
@Import(GatewayTestRouteConfig.class)
class RbacAuthGlobalFilterTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ==================== 越权访问 → 403 ====================

    @Test
    void shouldReturn403WhenFamilyAccessAdminPath() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(10L, "familyUser", Set.of(UserRole.FAMILY)));

        webTestClient.get().uri("/admin/users")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110002)
                .jsonPath("$.msg").isEqualTo(SystemErrorCode.FORBIDDEN.getMsg());
    }

    @Test
    void shouldReturn403WhenCaregiverAccessAdminPath() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(11L, "caregiver", Set.of(UserRole.CAREGIVER)));

        webTestClient.get().uri("/admin/dashboard")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110002)
                .jsonPath("$.msg").isEqualTo(SystemErrorCode.FORBIDDEN.getMsg());
    }

    @Test
    void shouldReturn403WhenFamilyAccessServerPath() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(12L, "familyUser", Set.of(UserRole.FAMILY)));

        webTestClient.get().uri("/server/devices")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110002);
    }

    // ==================== 合法访问 → 200 ====================

    @Test
    void shouldReturn200WhenAdminAccessAdminPath() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(1L, "admin", Set.of(UserRole.ADMIN)));

        webTestClient.get().uri("/admin/users")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Id").isEqualTo("1")
                .jsonPath("$.X-Username").isEqualTo("admin")
                .jsonPath("$.X-User-Roles").isEqualTo("ADMIN");
    }

    @Test
    void shouldReturn200WhenFamilyAccessFamilyPath() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(20L, "familyUser", Set.of(UserRole.FAMILY)));

        webTestClient.get().uri("/family/notifications")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Id").isEqualTo("20")
                .jsonPath("$.X-Username").isEqualTo("familyUser")
                .jsonPath("$.X-User-Roles").isEqualTo("FAMILY");
    }

    @Test
    void shouldReturn200WhenCaregiverAccessElderlyPath() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(30L, "caregiver", Set.of(UserRole.CAREGIVER)));

        webTestClient.get().uri("/elderly/vitals")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Id").isEqualTo("30")
                .jsonPath("$.X-Username").isEqualTo("caregiver")
                .jsonPath("$.X-User-Roles").isEqualTo("CAREGIVER");
    }

    @Test
    void shouldReturn200WhenOperatorAccessAdminPath() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(40L, "operator", Set.of(UserRole.OPERATOR)));

        webTestClient.get().uri("/admin/dashboard")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Id").isEqualTo("40")
                .jsonPath("$.X-Username").isEqualTo("operator")
                .jsonPath("$.X-User-Roles").isEqualTo("OPERATOR");
    }

    // ==================== 无角色用户 → 403 ====================

    @Test
    void shouldReturn403WhenNoRoleUserAccessProtectedPath() {
        // 用户已认证但无任何角色
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(99L, "noRoleUser", Set.of()));

        webTestClient.get().uri("/admin/users")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110002);
    }

    // ==================== 多角色用户 ====================

    @Test
    void shouldReturn200WhenMultiRoleUserAccessAnyMatchingPath() {
        // 用户同时拥有 FAMILY 和 CAREGIVER 角色
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(50L, "multiRole", Set.of(UserRole.FAMILY, UserRole.CAREGIVER)));

        // 应能访问 /family/**（FAMILY 角色匹配）
        Set<String> expectedRoles = Arrays.stream(
                new String[]{"FAMILY", "CAREGIVER"}).collect(Collectors.toSet());

        webTestClient.get().uri("/family/dashboard")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Roles").value((String roles) -> {
                    Set<String> actualRoles = Arrays.stream(roles.split(","))
                            .collect(Collectors.toSet());
                    assert actualRoles.containsAll(expectedRoles)
                            : "Expected roles to contain FAMILY and CAREGIVER, but got: " + roles;
                });

        // 应能访问 /elderly/**（CAREGIVER 角色匹配）
        webTestClient.get().uri("/elderly/health")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== 白名单路径不受 RBAC 限制 ====================

    @Test
    void shouldPassWhitelistPathRegardlessOfRoles() {
        // 白名单路径 /auth/login 应直接放行（即使无 Token）
        // 预期：非 401/403 即视为放行（实际可能因下游不可达返回 5xx）
        webTestClient.post().uri("/auth/login")
                .exchange()
                .expectStatus().is5xxServerError();  // 非 401/403 = 放行
    }
}
