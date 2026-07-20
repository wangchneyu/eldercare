package com.eldercare.gateway.filter;

import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import com.eldercare.gateway.GatewayApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

/**
 * Gateway JWT 鉴权集成测试
 * <p>
 * 验证 JwtAuthGlobalFilter 的完整行为:
 * <ol>
 *   <li>无 Token → 401</li>
 *   <li>无效 Token → 401</li>
 *   <li>正常 Token → 200 且下游收到透传 Header</li>
 *   <li>白名单路径放行</li>
 *   <li>防伪造 X-User-* 头清除</li>
 * </ol>
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
class JwtAuthGlobalFilterTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ==================== 场景 1: 无 Token → 401 ====================

    @Test
    void shouldReturn401WhenNoToken() {
        webTestClient.get().uri("/admin/users")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110001)
                .jsonPath("$.msg").isEqualTo(SystemErrorCode.UNAUTHORIZED.getMsg());
    }

    // ==================== 场景 2: 无效 Token → 401 ====================

    @Test
    void shouldReturn401WhenInvalidToken() {
        webTestClient.get().uri("/admin/users")
                .header("Authorization", "Bearer invalid.token.here")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110001)
                .jsonPath("$.msg").isEqualTo(SystemErrorCode.UNAUTHORIZED.getMsg());
    }

    // ==================== 场景 3: 正常 Token → 200 + 透传 Header ====================

    @Test
    void shouldReturn200AndForwardUserHeaders() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(1L, "admin", Set.of(UserRole.ADMIN)));

        webTestClient.get().uri("/test-downstream/users")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Id").isEqualTo("1")
                .jsonPath("$.X-Username").isEqualTo("admin")
                .jsonPath("$.X-User-Roles").isEqualTo("ADMIN");
    }

    // ==================== 场景 4: 白名单路径放行 ====================

    @Test
    void shouldPassWhitelistPathWithoutToken() {
        // 白名单路径只验证 gateway 层不拦截鉴权（不返回 401），
        // 实际路由到下游 service-auth 不可达，返回 5xx 是预期行为
        webTestClient.post().uri("/auth/login")
                .exchange()
                .expectStatus().is5xxServerError();  // 非 401 即视为放行
    }

    // ==================== 场景 5: 防伪造 X-User-* 头 ====================

    @Test
    void shouldStripExternalUserHeaders() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(2L, "realUser", Set.of(UserRole.CAREGIVER)));

        // 外部伪造 admin 身份的头应被清除，下游只收到 Token 解析出的真实值
        webTestClient.get().uri("/test-downstream/users")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "999")
                .header("X-Username", "hacker")
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Id").isEqualTo("2")
                .jsonPath("$.X-Username").isEqualTo("realUser")
                .jsonPath("$.X-User-Roles").isEqualTo("CAREGIVER");
    }
}
