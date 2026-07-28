package com.eldercare.iot.controller;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.feign.dto.iot.DeviceBindingSnapshotRemoteDTO;
import com.eldercare.common.feign.dto.iot.DeviceSnapshotRemoteDTO;
import com.eldercare.iot.config.WebFluxConfig;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.exception.IotReactiveExceptionHandler;
import com.eldercare.iot.service.IDeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = InternalDeviceController.class)
@Import({InternalDeviceController.class, WebFluxConfig.class, IotReactiveExceptionHandler.class})
@ContextConfiguration(classes = InternalDeviceControllerTest.TestApplication.class)
@ActiveProfiles("test")
class InternalDeviceControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    IDeviceService deviceService;

    @Test
    void snapshotReturnsRawRemoteDtoAndRunsBlockingWorkOffEventLoop() {
        AtomicReference<String> threadName = new AtomicReference<>();
        DeviceSnapshotRemoteDTO snapshot = snapshot();
        when(deviceService.getSnapshot("DEV-001")).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            return snapshot;
        });

        assertTimeout(Duration.ofMillis(500), () -> webTestClient.get()
                .uri("/internal/iot/devices/DEV-001/snapshot")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deviceId").isEqualTo("DEV-001")
                .jsonPath("$.onlineStatus").isEqualTo("ONLINE")
                .jsonPath("$.lifecycleStatus").isEqualTo("ACTIVE")
                .jsonPath("$.bindings[0].bindingType").isEqualTo("ELDER")
                .jsonPath("$.code").doesNotExist()
                .jsonPath("$.data").doesNotExist());

        assertFalse(threadName.get().startsWith("reactor-http-nio"),
                "阻塞 Controller 不得运行在 Reactor Netty EventLoop");
    }

    @Test
    void missingDeviceReturns404() {
        when(deviceService.getSnapshot("MISSING"))
                .thenThrow(new BizException(IotErrorCode.DEVICE_NOT_FOUND));

        webTestClient.get()
                .uri("/internal/iot/devices/MISSING/snapshot")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(212001);
    }

    private DeviceSnapshotRemoteDTO snapshot() {
        DeviceBindingSnapshotRemoteDTO binding = new DeviceBindingSnapshotRemoteDTO();
        binding.setBindingId("B-001");
        binding.setBindingType("ELDER");
        binding.setElderId("1001");

        DeviceSnapshotRemoteDTO snapshot = new DeviceSnapshotRemoteDTO();
        snapshot.setDeviceId("DEV-001");
        snapshot.setDeviceType("RADAR");
        snapshot.setOnlineStatus("ONLINE");
        snapshot.setLifecycleStatus("ACTIVE");
        snapshot.setLastHeartbeatAt(OffsetDateTime.now());
        snapshot.setSnapshotTime(OffsetDateTime.now());
        snapshot.setBindings(List.of(binding));
        return snapshot;
    }

    @EnableAutoConfiguration
    static class TestApplication {
    }
}
