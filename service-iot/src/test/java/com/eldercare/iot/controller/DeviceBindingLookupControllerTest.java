package com.eldercare.iot.controller;

import com.eldercare.iot.config.WebFluxConfig;
import com.eldercare.iot.dto.vo.DeviceBindingVO;
import com.eldercare.iot.exception.IotReactiveExceptionHandler;
import com.eldercare.iot.service.IDeviceBindingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = DeviceBindingLookupController.class)
@Import({DeviceBindingLookupController.class, WebFluxConfig.class, IotReactiveExceptionHandler.class})
@ContextConfiguration(classes = DeviceBindingLookupControllerTest.TestApplication.class)
@ActiveProfiles("test")
class DeviceBindingLookupControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    IDeviceBindingService deviceBindingService;

    @Test
    void lookupReturnsOnlyActiveBindingsOffEventLoop() {
        AtomicReference<String> threadName = new AtomicReference<>();
        DeviceBindingVO binding = new DeviceBindingVO();
        binding.setDeviceId("DEV-001");
        binding.setBindingType("ELDER");
        binding.setElderId(1001L);
        binding.setStatus("ACTIVE");
        when(deviceBindingService.getActiveByElderId(1001L)).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            return List.of(binding);
        });

        webTestClient.get()
                .uri("/iot/bindings?elderId=1001&status=ACTIVE")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].deviceId").isEqualTo("DEV-001")
                .jsonPath("$.data[0].bindingType").isEqualTo("ELDER")
                .jsonPath("$.data[0].elderId").isEqualTo("1001");

        assertFalse(threadName.get().startsWith("reactor-http-nio"));
    }

    @Test
    void lookupRejectsInactiveStatus() {
        webTestClient.get()
                .uri("/iot/bindings?elderId=1001&status=INACTIVE")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(212008);

        verify(deviceBindingService, never()).getActiveByElderId(1001L);
    }

    @EnableAutoConfiguration
    static class TestApplication {
    }
}
