package com.eldercare.iot.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eldercare.common.redis.lock.RedisLock;
import com.eldercare.common.redis.service.RedisService;
import com.eldercare.iot.controller.DeviceModelController;
import com.eldercare.iot.exception.IotReactiveExceptionHandler;
import com.eldercare.iot.service.IDeviceModelService;
import com.eldercare.iot.support.IdempotencyService;
import com.eldercare.iot.support.IotAuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ContextConfiguration;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = DeviceModelController.class)
@Import({DeviceModelController.class, WebFluxConfig.class, IotReactiveExceptionHandler.class,
        IotTraceWebFilter.class, IdempotencyService.class, IotAuditLogger.class})
@ContextConfiguration(classes = WebFluxRuntimeTest.TestApplication.class)
@ActiveProfiles("test")
class WebFluxRuntimeTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private IDeviceModelService deviceModelService;

    /**
     * Redis 以 Mock 代替：本类验证 WebFlux 运行期行为（路由、响应封装、TraceId），
     * 幂等回放语义由 {@link DeviceModelIdempotencyControllerTest} 覆盖；且 Redis
     * 天然 last-write-wins，故障刷新场景不适合在此混合断言。
     */
    @MockBean
    private RedisService redisService;

    @MockBean
    private RedisLock redisLock;

    @Test
    void deviceModelListIsServedByWebFlux() {
        when(deviceModelService.list(nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(new Page<>());

        webTestClient.get()
                .uri("/iot/device-models?pageNo=1&pageSize=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.records").isArray();
    }

    @Test
    void restTraceIdIsReturnedAndPropagatedToResponseBody() {
        when(deviceModelService.list(nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(new Page<>());

        webTestClient.get()
                .uri("/iot/device-models?pageNo=1&pageSize=20")
                .header(IotTraceWebFilter.TRACE_ID_HEADER, "rest-trace-001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(IotTraceWebFilter.TRACE_ID_HEADER, "rest-trace-001")
                .expectBody()
                .jsonPath("$.traceId").isEqualTo("rest-trace-001");
    }

    @Test
    void missingRouteReturnsNotFoundInsteadOfInternalServerError() {
        webTestClient.get()
                .uri("/iot/missing")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(100009);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
