package com.eldercare.iot.controller;

import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.dto.request.DeviceModelCreateRequest;
import com.eldercare.iot.dto.request.DeviceModelUpdateRequest;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.support.IotAuditLogger;
import com.eldercare.iot.support.WebTestClientMvcAdapter;
import com.eldercare.iot.support.WebTestClientMvcAdapter.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.eldercare.iot.support.WebTestClientMvcAdapter.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1-01 API 验收：型号 POST/PUT/DELETE 的真实 Idempotency-Key 回放（需要
 * localhost:5432 PostgreSQL 与 localhost:6379 Redis 可用）。
 * 每个测试使用独立 Key 与 modelCode，避免跨用例串扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeviceModelIdempotencyApiTest {

    @Autowired private WebTestClient webTestClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IotDeviceModelMapper modelMapper;
    @SpyBean private IotAuditLogger auditLogger;

    private WebTestClientMvcAdapter mockMvc;

    @BeforeEach
    void setUpClient() {
        mockMvc = new WebTestClientMvcAdapter(webTestClient, objectMapper);
    }

    @Test
    @Order(1)
    void create_replay_withSameKeyAndSameBody() throws Exception {
        String key = "api-create-key-" + System.currentTimeMillis();
        String modelCode = "API-REPLAY-" + System.currentTimeMillis();
        DeviceModelCreateRequest req = modelCreateRequest(modelCode, "回放测试描述");

        MvcResult first = mockMvc.perform(post("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-User-Id", "api-user-1")
                .header("X-Username", "api-tester")
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.modelCode").value(modelCode))
            .andReturn();

        String firstId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("id").asText();
        assertNotNull(firstId);

        MvcResult replay = mockMvc.perform(post("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        String replayId = objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("data").path("id").asText();
        assertEquals(firstId, replayId, "同 Key 同请求回放必须返回首次响应");

        Long count = modelMapper.selectCount(new LambdaQueryWrapper<IotDeviceModel>()
                .eq(IotDeviceModel::getModelCode, modelCode));
        assertEquals(1L, count, "同一请求只应落库一次");
        Mockito.verify(auditLogger, Mockito.times(1))
                .success(Mockito.eq("MODEL_CREATE"), Mockito.eq(firstId), Mockito.eq(modelCode), Mockito.any());
    }

    @Test
    @Order(2)
    void create_sameKeyDifferentBody_conflicts() throws Exception {
        String key = "api-create-conflict-" + System.currentTimeMillis();
        String modelCode = "API-CONFLICT-" + System.currentTimeMillis();

        mockMvc.perform(post("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(objectMapper.writeValueAsString(modelCreateRequest(modelCode, "第一次描述"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(objectMapper.writeValueAsString(modelCreateRequest(modelCode, "第二次描述"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(212009));

        Long count = modelMapper.selectCount(new LambdaQueryWrapper<IotDeviceModel>()
                .eq(IotDeviceModel::getModelCode, modelCode));
        assertEquals(1L, count, "冲突请求不得创建第二行");
    }

    @Test
    @Order(3)
    void create_withoutKey_keepsOriginalBehavior() throws Exception {
        String modelCode = "API-NOKEY-" + System.currentTimeMillis();

        mockMvc.perform(post("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelCreateRequest(modelCode, "无 Key"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0));

        Long count = modelMapper.selectCount(new LambdaQueryWrapper<IotDeviceModel>()
                .eq(IotDeviceModel::getModelCode, modelCode));
        assertEquals(1L, count);
    }

    @Test
    @Order(4)
    void update_replay_sameKeySameBody() throws Exception {
        String modelCode = "API-UPDATE-REPLAY-" + System.currentTimeMillis();
        MvcResult created = mockMvc.perform(post("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelCreateRequest(modelCode, "初始描述"))))
            .andExpect(status().isCreated())
            .andReturn();
        long modelId = Long.parseLong(objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText());

        IotDeviceModel before = modelMapper.selectById(modelId);
        String key = "api-update-key-" + System.currentTimeMillis();
        DeviceModelUpdateRequest req = new DeviceModelUpdateRequest();
        req.setHeartbeatTimeoutSeconds(90);
        req.setDescription("更新后的描述");
        req.setVersion(before.getVersion());

        String body = objectMapper.writeValueAsString(req);
        mockMvc.perform(put("/iot/device-models/{modelId}", modelId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(put("/iot/device-models/{modelId}", modelId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        IotDeviceModel after = modelMapper.selectById(modelId);
        assertEquals(90, after.getHeartbeatTimeoutSeconds());
        assertEquals("更新后的描述", after.getDescription());
        assertEquals(before.getVersion() + 1, after.getVersion(), "回放不得再次累加版本");
    }

    @Test
    @Order(5)
    void delete_replay_returnsFirstSuccessNotSecondNotFound() throws Exception {
        String modelCode = "API-DELETE-REPLAY-" + System.currentTimeMillis();
        MvcResult created = mockMvc.perform(post("/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modelCreateRequest(modelCode, "待删除"))))
            .andExpect(status().isCreated())
            .andReturn();
        long modelId = Long.parseLong(objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText());

        String key = "api-delete-key-" + System.currentTimeMillis();
        mockMvc.perform(delete("/iot/device-models/{modelId}", modelId)
                .header("Idempotency-Key", key))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        // 第二次真实删除会 404；幂等回放必须仍返回首次成功语义（200 + R.ok(null)）
        mockMvc.perform(delete("/iot/device-models/{modelId}", modelId)
                .header("Idempotency-Key", key))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        assertNull(modelMapper.selectById(modelId), "型号应已被首次调用删除");
    }

    private DeviceModelCreateRequest modelCreateRequest(String modelCode, String description) {
        DeviceModelCreateRequest req = new DeviceModelCreateRequest();
        req.setModelCode(modelCode);
        req.setManufacturer("ApiCorp");
        req.setDeviceType("MATTRESS");
        req.setParserCode("simulator");
        req.setHeartbeatTimeoutSeconds(30);
        req.setDescription(description);
        return req;
    }
}