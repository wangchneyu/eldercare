package com.eldercare.iot.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eldercare.iot.controller.DeviceModelController;
import com.eldercare.iot.exception.IotReactiveExceptionHandler;
import com.eldercare.iot.service.IDeviceModelService;
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
@Import({DeviceModelController.class, WebFluxConfig.class, IotReactiveExceptionHandler.class})
@ContextConfiguration(classes = WebFluxRuntimeTest.TestApplication.class)
@ActiveProfiles("test")
class WebFluxRuntimeTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private IDeviceModelService deviceModelService;

    @Test
    void deviceModelListIsServedByWebFlux() {
        when(deviceModelService.list(nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(new Page<>());

        webTestClient.get()
                .uri("/api/iot/device-models?page=1&size=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.records").isArray();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
