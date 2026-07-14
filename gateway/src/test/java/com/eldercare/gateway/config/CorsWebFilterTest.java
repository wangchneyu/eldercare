package com.eldercare.gateway.config;

import com.eldercare.gateway.GatewayApplication;
import com.eldercare.gateway.filter.GatewayTestRouteConfig;
import com.eldercare.security.domain.LoginUser;
import com.eldercare.security.domain.UserRole;
import com.eldercare.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

/**
 * CORS 跨域集成测试
 * <p>
 * 验证 CorsWebFilter 的跨域行为:
 * <ol>
 *   <li>OPTIONS 预检请求 → 200 + CORS 响应头</li>
 *   <li>GET 请求带 Origin → 响应包含 CORS 头</li>
 *   <li>Access-Control-Allow-Credentials 存在</li>
 * </ol>
 * <p>
 * 注意：使用 bindToServer() 手动构建 WebTestClient（而非 @AutoConfigureWebTestClient），
 * 以确保 CorsUtils.isSameOrigin() 能正确获取请求 scheme。
 */
@SpringBootTest(classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "eldercare.security.enabled=true",
                "eldercare.security.secret=GatewayTestSecretKey2024!@#$%^&*(256bit)"
        })
@Import(GatewayTestRouteConfig.class)
class CorsWebFilterTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private static final String TEST_ORIGIN = "http://example.com";

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ==================== 预检请求（OPTIONS） ====================

    @Test
    void shouldReturnCorsHeadersForPreflight() {
        webTestClient.options().uri("/admin/users")
                .header(HttpHeaders.ORIGIN, TEST_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, TEST_ORIGIN)
                .expectHeader().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }

    // ==================== 普通 GET 请求带 Origin ====================

    @Test
    void shouldReturnCorsHeadersForGetRequest() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(1L, "admin", Set.of(UserRole.ADMIN)));

        webTestClient.get().uri("/test-downstream/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, TEST_ORIGIN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, TEST_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    // ==================== Credentials 验证 ====================

    @Test
    void shouldAllowCredentials() {
        String token = jwtTokenProvider.createAccessToken(
                new LoginUser(1L, "admin", Set.of(UserRole.ADMIN)));

        webTestClient.get().uri("/test-downstream/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, TEST_ORIGIN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }
}
