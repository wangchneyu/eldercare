package com.eldercare.iot.webflux;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.redis.lock.RedisLock;
import com.eldercare.common.redis.service.RedisService;
import com.eldercare.iot.config.IotTraceWebFilter;
import com.eldercare.iot.config.WebFluxConfig;
import com.eldercare.iot.controller.DeviceModelController;
import com.eldercare.iot.dto.request.DeviceModelCreateRequest;
import com.eldercare.iot.dto.request.DeviceModelUpdateRequest;
import com.eldercare.iot.dto.vo.DeviceModelVO;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.exception.IotReactiveExceptionHandler;
import com.eldercare.iot.service.IDeviceModelService;
import com.eldercare.iot.support.IdempotencyService;
import com.eldercare.iot.support.IotAuditLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-01 聚焦测试：型号写接口的 Idempotency-Key 回放/冲突/无 Key 语义、
 * 结构化审计字段（含失败结果）、删除重放语义与 Redis 不可用时的依赖故障语义。
 * 使用真实 {@link IdempotencyService}，Redis 以 Mock 代替，不依赖外部服务。
 * <p>
 * 本类特意放在独立 {@code webflux} 包：嵌套 {@code @SpringBootConfiguration}
 * 只供本 slice 使用，不会遮蔽 {@code controller} 包中 {@code @SpringBootTest}
 * 用例对 {@code ServiceIotApplication} 的上下文发现。
 */
@WebFluxTest(controllers = DeviceModelController.class)
@Import({DeviceModelController.class, WebFluxConfig.class, IotReactiveExceptionHandler.class,
        IotTraceWebFilter.class, IdempotencyService.class, IotAuditLogger.class})
@ContextConfiguration(classes = DeviceModelIdempotencyControllerTest.TestApplication.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class DeviceModelIdempotencyControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IDeviceModelService deviceModelService;

    @MockBean
    private RedisService redisService;

    @MockBean
    private RedisLock redisLock;

    @SpyBean
    private IotAuditLogger auditLogger;

    private AtomicReference<String> redisValue = new AtomicReference<>();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @BeforeEach
    void setUp() {
        redisValue.set(null);
        when(redisLock.tryLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redisLock.unlock(anyString(), anyString())).thenReturn(true);
        when(redisService.get(anyString())).thenAnswer(inv -> redisValue.get());
        doAnswer(inv -> {
            redisValue.set(String.valueOf((Object) inv.getArgument(1)));
            return null;
        }).when(redisService).set(anyString(), any(), anyLong());
    }

    // ==================== 创建 / 回放 / 冲突 ====================

    @Test
    void create_sameKeySameRequest_executesOnceAndReplaysNotOnNettyEventLoop() {
        String key = "create-key-1";
        AtomicReference<String> executingThread = new AtomicReference<>();
        when(deviceModelService.create(any()))
                .thenAnswer(inv -> {
                    executingThread.set(Thread.currentThread().getName());
                    return modelVO(1001L, "MC-REPLAY-1");
                });

        Object createBody = createRequest("MC-REPLAY-1", "描述-A");
        webTestClient.post().uri("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-User-Id", "user-1")
                .header("X-Username", "wangyze")
                .bodyValue(createBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.id").isEqualTo("1001");

        assertTrue(executingThread.get().startsWith("iot-blocking-"),
                "阻塞写操作线程应为 iot-blocking-*，实际：" + executingThread.get());
        assertTrue(!executingThread.get().startsWith("reactor-http-nio"),
                "写操作不得占用 reactor-http-nio-*，实际：" + executingThread.get());

        // 同 Key 同请求：回放缓存，不再次执行业务动作，返回同一 ID
        webTestClient.post().uri("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .bodyValue(createBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.id").isEqualTo("1001");

        verify(deviceModelService, times(1)).create(any());
        verify(auditLogger, times(1)).success(eq("MODEL_CREATE"), eq("1001"), eq("MC-REPLAY-1"), any());
    }

    @Test
    void create_sameKeyDifferentRequest_conflicts() {
        String key = "create-key-conflict";
        DeviceModelCreateRequest first = createRequest("MC-CONFLICT-1", "描述-A");
        redisValue.set(fingerprint(first) + "\n" + serialize(modelVO(2001L, "MC-CONFLICT-1")));

        webTestClient.post().uri("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .bodyValue(createRequest("MC-CONFLICT-1", "描述-B"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo(212009);

        verify(deviceModelService, never()).create(any());
        verify(auditLogger).failure(eq("MODEL_CREATE"), isNull(), eq("MC-CONFLICT-1"), any());
    }

    @Test
    void create_noKey_keepsOriginalBehaviorWithoutRedis() {
        when(deviceModelService.create(any())).thenReturn(modelVO(3001L, "MC-NOKEY-1"));

        webTestClient.post().uri("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createRequest("MC-NOKEY-1", "无 Key 描述"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        verify(deviceModelService, times(1)).create(any());
        verify(redisService, never()).get(anyString());
        verify(redisService, never()).set(anyString(), any(), anyLong());
    }

    // ==================== 修改 / 删除 ====================

    @Test
    void update_sameKeySameRequest_executesOnceAndReplays() {
        String key = "update-key-1";
        when(deviceModelService.update(any(), any())).thenReturn(modelVO(4001L, "MC-UPD-1"));

        DeviceModelUpdateRequest req = updateRequest(2);
        webTestClient.put().uri("/iot/device-models/{modelId}", 4001L)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        webTestClient.put().uri("/iot/device-models/{modelId}", 4001L)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        verify(deviceModelService, times(1)).update(eq(4001L), any());
        verify(auditLogger, times(1)).success(eq("MODEL_UPDATE"), eq("4001"), eq("MC-UPD-1"), any());
    }

    @Test
    void delete_replayReturnsFirstSuccessWithoutSecondDelete() {
        String key = "delete-key-1";
        redisValue.set(fingerprint("4002") + "\n\"INSTANCE\"");
        doThrow(new AssertionError("幂等回放不应再次执行删除")).when(deviceModelService).delete(4002L);

        webTestClient.delete().uri("/iot/device-models/{modelId}", 4002L)
                .header("Idempotency-Key", key)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        verify(deviceModelService, never()).delete(4002L);
    }

    @Test
    void delete_failingActionIsNotCachedAsSuccess() {
        String key = "delete-key-fail";
        doThrow(new BizException(IotErrorCode.DEVICE_MODEL_NOT_FOUND)).when(deviceModelService).delete(5000L);

        // 首次失败：404 且不得写入成功回放
        webTestClient.delete().uri("/iot/device-models/{modelId}", 5000L)
                .header("Idempotency-Key", key)
                .exchange()
                .expectStatus().isNotFound();

        assertNull(redisValue.get(), "失败请求不得被缓存为成功回放");
        verify(auditLogger).failure(eq("MODEL_DELETE"), eq("5000"), isNull(), any());
    }

    // ==================== Redis 不可用 ====================

    @Test
    void redisUnavailable_keyedRequestFailsAndNeverSilentlyLosesIdempotency() {
        when(redisService.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(deviceModelService.create(any())).thenReturn(modelVO(6001L, "MC-REDIS-DOWN"));

        webTestClient.post().uri("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "redis-down-key")
                .bodyValue(createRequest("MC-REDIS-DOWN", "描述"))
                .exchange()
                .expectStatus().isEqualTo(500);

        verify(deviceModelService, never()).create(any());
    }

    // ==================== 审计字段 ====================

    @Test
    void auditContainsOperatorModelAndNoSensitiveBodyOrKey(CapturedOutput output) {
        String key = "audit-key-9";
        String sentinel = "审计敏感描述SENTINEL-9";
        when(deviceModelService.create(any())).thenReturn(modelVO(7001L, "MC-AUDIT-1"));

        webTestClient.post().uri("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-User-Id", "user-7")
                .header("X-Username", "wangyze")
                .bodyValue(createRequest("MC-AUDIT-1", sentinel))
                .exchange()
                .expectStatus().isCreated();

        String logs = output.getOut();
        assertTrue(logs.contains("iot_audit operation=MODEL_CREATE"), "缺少 MODEL_CREATE 审计日志");
        assertTrue(logs.contains("modelId=7001"), "审计缺少 modelId");
        assertTrue(logs.contains("modelCode=MC-AUDIT-1"), "审计缺少 modelCode");
        assertTrue(logs.contains("userId=user-7"), "审计缺少 X-User-Id");
        assertTrue(logs.contains("username=wangyze"), "审计缺少 X-Username");
        assertTrue(logs.contains("traceId="), "审计缺少 traceId");
        assertTrue(logs.contains("result=SUCCESS"), "审计缺少结果");
        assertTrue(!logs.contains(sentinel), "审计日志泄露请求体敏感内容");
        assertTrue(!logs.contains(key), "审计日志泄露 Idempotency-Key");
    }

    // ==================== 辅助 ====================

    private DeviceModelCreateRequest createRequest(String modelCode, String description) {
        DeviceModelCreateRequest req = new DeviceModelCreateRequest();
        req.setModelCode(modelCode);
        req.setManufacturer("TestCorp");
        req.setDeviceType("MATTRESS");
        req.setParserCode("simulator");
        req.setHeartbeatTimeoutSeconds(30);
        req.setDescription(description);
        return req;
    }

    private DeviceModelUpdateRequest updateRequest(int version) {
        DeviceModelUpdateRequest req = new DeviceModelUpdateRequest();
        req.setHeartbeatTimeoutSeconds(60);
        req.setVersion(version);
        return req;
    }

    private DeviceModelVO modelVO(Long id, String modelCode) {
        DeviceModelVO vo = new DeviceModelVO();
        vo.setId(id);
        vo.setModelCode(modelCode);
        vo.setManufacturer("TestCorp");
        vo.setDeviceType("MATTRESS");
        vo.setParserCode("simulator");
        vo.setHeartbeatTimeoutSeconds(30);
        vo.setEnabled(true);
        vo.setVersion(0);
        vo.setCreatedAt(OffsetDateTime.now());
        vo.setUpdatedAt(OffsetDateTime.now());
        return vo;
    }

    private String fingerprint(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(request));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}