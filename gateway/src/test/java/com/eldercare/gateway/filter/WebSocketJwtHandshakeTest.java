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

import java.util.Set;

/**
 * WebSocket 握手阶段 JWT 校验集成测试
 * <p>
 * 模拟浏览器 WebSocket 升级请求，验证 JWT 从查询参数 ?token= 提取和校验:
 * <ol>
 *   <li>无 Token 的 WebSocket 升级请求 → 401</li>
 *   <li>无效 Token 的 WebSocket 升级请求 → 401</li>
 *   <li>有效 Token 的 WebSocket 升级请求 → 200 + 身份回显</li>
 * </ol>
 * <p>
 * 注意: WebTestClient 无法完成真正的 WebSocket 连接，但可以模拟 HTTP 升级请求
 * 并验证 gateway 的 JWT 鉴权是否正确拦截/放行。
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
class WebSocketJwtHandshakeTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ==================== 无 Token → 401 ====================

    @Test
    void shouldReturn401WhenWebSocketUpgradeWithoutToken() {
        webTestClient.get().uri("/ws/test/alert")
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110001)
                .jsonPath("$.msg").isEqualTo(SystemErrorCode.UNAUTHORIZED.getMsg());
    }

    // ==================== 无效 Token → 401 ====================

    @Test
    void shouldReturn401WhenWebSocketUpgradeWithInvalidToken() {
        webTestClient.get().uri("/ws/test/alert?token=invalid.token.here")
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo(110001)
                .jsonPath("$.msg").isEqualTo(SystemErrorCode.UNAUTHORIZED.getMsg());
    }

    // ==================== 有效 Token → 200 + 身份回显 ====================

    @Test
    void shouldPassWhenWebSocketUpgradeWithValidToken() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(1L, "wsUser", Set.of(UserRole.ADMIN)));

        webTestClient.get().uri("/ws/test/alert?token=" + token)
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.X-User-Id").isEqualTo("1")
                .jsonPath("$.X-Username").isEqualTo("wsUser")
                .jsonPath("$.X-User-Roles").isEqualTo("ADMIN");
    }
}
