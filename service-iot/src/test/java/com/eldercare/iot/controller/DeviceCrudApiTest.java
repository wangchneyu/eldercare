package com.eldercare.iot.controller;

import com.eldercare.iot.dto.request.*;
import com.eldercare.iot.dto.vo.*;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.support.WebTestClientMvcAdapter;
import com.eldercare.iot.support.WebTestClientMvcAdapter.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;

import static com.eldercare.iot.support.WebTestClientMvcAdapter.*;

/**
 * 阶段三验证 — 设备管理 CRUD 全流程 API 测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeviceCrudApiTest {

    @Autowired private WebTestClient webTestClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IotDeviceModelMapper modelMapper;
    @Autowired private IotDeviceInstanceMapper instanceMapper;
    @Autowired private IotDeviceBindingMapper bindingMapper;

    private static String testModelId;
    private static String testDeviceId;
    private static String bindTestDeviceId;

    private WebTestClientMvcAdapter mockMvc;

    @BeforeEach
    void setUpClient() {
        mockMvc = new WebTestClientMvcAdapter(webTestClient, objectMapper);
    }

    // ==================== 型号 CRUD ====================

    @Test
    @Order(1)
    void model_create() throws Exception {
        DeviceModelCreateRequest req = new DeviceModelCreateRequest();
        req.setModelCode("TEST-CRUD-" + System.currentTimeMillis());
        req.setManufacturer("TestCorp");
        req.setDeviceType("MATTRESS");
        req.setParserCode("simulator");
        req.setHeartbeatTimeoutSeconds(30);
        req.setDescription("CRUD 测试型号");

        MvcResult result = mockMvc.perform(post("/api/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.modelCode").value(req.getModelCode()))
            .andExpect(jsonPath("$.data.id").exists())
            .andReturn();

        // 提取 modelId
        String body = result.getResponse().getContentAsString();
        testModelId = objectMapper.readTree(body).get("data").get("id").asText();
    }

    @Test
    @Order(2)
    void model_create_duplicate_code() throws Exception {
        // 先查一下刚才创建的型号
        var existing = modelMapper.selectById(Long.valueOf(testModelId));

        DeviceModelCreateRequest req = new DeviceModelCreateRequest();
        req.setModelCode(existing.getModelCode()); // 重复
        req.setManufacturer("Other");
        req.setDeviceType("RADAR");
        req.setParserCode("simulator");
        req.setHeartbeatTimeoutSeconds(15);

        mockMvc.perform(post("/api/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(212012));
    }

    @Test
    @Order(3)
    void model_list() throws Exception {
        mockMvc.perform(get("/api/iot/device-models")
                .param("page", "1")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @Order(4)
    void model_update() throws Exception {
        var model = modelMapper.selectById(Long.valueOf(testModelId));

        DeviceModelUpdateRequest req = new DeviceModelUpdateRequest();
        req.setHeartbeatTimeoutSeconds(60);
        req.setDescription("已更新");
        req.setVersion(model.getVersion());

        mockMvc.perform(put("/api/iot/device-models/{modelId}", testModelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.heartbeatTimeoutSeconds").value(60));
    }

    @Test
    @Order(5)
    void model_update_optimistic_lock() throws Exception {
        DeviceModelUpdateRequest req = new DeviceModelUpdateRequest();
        req.setHeartbeatTimeoutSeconds(99);
        req.setVersion(0); // 旧版本号

        mockMvc.perform(put("/api/iot/device-models/{modelId}", testModelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict());
    }

    // ==================== 设备 CRUD ====================

    @Test
    @Order(10)
    void device_register() throws Exception {
        DeviceRegisterRequest req = new DeviceRegisterRequest();
        req.setModelId(Long.valueOf(testModelId));
        req.setSerialNo("SN-CRUD-" + System.currentTimeMillis());
        req.setDeviceName("CRUD 测试设备");
        req.setMqttClientId("client-crud-" + System.currentTimeMillis());

        MvcResult result = mockMvc.perform(post("/api/iot/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.deviceId").exists())
            .andExpect(jsonPath("$.data.lifecycleStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.data.onlineStatus").value("UNKNOWN"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        testDeviceId = objectMapper.readTree(body).get("data").get("deviceId").asText();
    }

    @Test
    @Order(11)
    void device_register_duplicate_mqtt() throws Exception {
        String dupMqttId = "dup-mqtt-" + System.currentTimeMillis();

        DeviceRegisterRequest req = new DeviceRegisterRequest();
        req.setModelId(Long.valueOf(testModelId));
        req.setSerialNo("SN-DUP1-" + System.currentTimeMillis());
        req.setDeviceName("dup test 1");
        req.setMqttClientId(dupMqttId);

        // 先注册一个
        mockMvc.perform(post("/api/iot/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        // 再用相同 mqttClientId 注册
        req.setSerialNo("SN-DUP2-" + System.currentTimeMillis());
        req.setDeviceName("dup test 2");
        mockMvc.perform(post("/api/iot/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(212002));
    }

    @Test
    @Order(12)
    void device_list() throws Exception {
        mockMvc.perform(get("/api/iot/devices")
                .param("page", "1")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @Order(13)
    void device_detail() throws Exception {
        mockMvc.perform(get("/api/iot/devices/{deviceId}", testDeviceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.deviceId").value(testDeviceId))
            .andExpect(jsonPath("$.data.modelCode").exists());
    }

    @Test
    @Order(14)
    void device_status() throws Exception {
        mockMvc.perform(get("/api/iot/devices/{deviceId}/status", testDeviceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.deviceId").value(testDeviceId))
            .andExpect(jsonPath("$.data.onlineStatus").value("UNKNOWN"));
    }

    @Test
    @Order(15)
    void device_lifecycle_disable() throws Exception {
        var instance = instanceMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.eldercare.iot.entity.IotDeviceInstance>()
                .eq(com.eldercare.iot.entity.IotDeviceInstance::getDeviceId, testDeviceId));

        DeviceLifecycleRequest req = new DeviceLifecycleRequest();
        req.setTargetStatus("DISABLED");
        req.setVersion(0);

        // IotDeviceInstance 没有 version 字段，lifecycle 用状态校验而非乐观锁
        mockMvc.perform(patch("/api/iot/devices/{deviceId}/lifecycle-status", testDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lifecycleStatus").value("DISABLED"));
    }

    @Test
    @Order(16)
    void device_lifecycle_invalid_transition() throws Exception {
        // 当前是 DISABLED，尝试回到 ACTIVE（不允许）
        DeviceLifecycleRequest req = new DeviceLifecycleRequest();
        req.setTargetStatus("ACTIVE");
        req.setVersion(0);

        mockMvc.perform(patch("/api/iot/devices/{deviceId}/lifecycle-status", testDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(212003));
    }

    @Test
    @Order(17)
    void device_lifecycle_retire() throws Exception {
        DeviceLifecycleRequest req = new DeviceLifecycleRequest();
        req.setTargetStatus("RETIRED");
        req.setVersion(0);

        mockMvc.perform(patch("/api/iot/devices/{deviceId}/lifecycle-status", testDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lifecycleStatus").value("RETIRED"));
    }

    @Test
    @Order(18)
    void device_lifecycle_retired_irreversible() throws Exception {
        DeviceLifecycleRequest req = new DeviceLifecycleRequest();
        req.setTargetStatus("ACTIVE");
        req.setVersion(0);

        mockMvc.perform(patch("/api/iot/devices/{deviceId}/lifecycle-status", testDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict());
    }

    // ==================== 绑定 CRUD ====================

    @Test
    @Order(20)
    void bind_requires_active_device() throws Exception {
        // testDeviceId 现在是 RETIRED，不能绑定
        DeviceBindRequest req = new DeviceBindRequest();
        req.setBindingType("LOCATION");
        req.setLocationId("LOC-001");
        req.setLocationType("PUBLIC_AREA");
        req.setLocationName("测试区域");
        req.setParkId("P001");

        mockMvc.perform(post("/api/iot/devices/{deviceId}/bindings", testDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(212003));
    }

    @Test
    @Order(21)
    void bind_location_success() throws Exception {
        // 创建一个新设备来测试绑定
        DeviceRegisterRequest regReq = new DeviceRegisterRequest();
        regReq.setModelId(Long.valueOf(testModelId));
        regReq.setSerialNo("SN-BIND-" + System.currentTimeMillis());
        regReq.setDeviceName("绑定测试设备");
        regReq.setMqttClientId("client-bind-" + System.currentTimeMillis());

        MvcResult regResult = mockMvc.perform(post("/api/iot/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)))
            .andExpect(status().isCreated())
            .andReturn();

        bindTestDeviceId = objectMapper.readTree(regResult.getResponse().getContentAsString())
            .get("data").get("deviceId").asText();

        // 绑定 LOCATION
        DeviceBindRequest req = new DeviceBindRequest();
        req.setBindingType("LOCATION");
        req.setLocationId("LOC-001");
        req.setLocationType("PUBLIC_AREA");
        req.setLocationName("三楼活动区");
        req.setParkId("P001");
        req.setBuildingId("B001");
        req.setFloorId("F03");

        mockMvc.perform(post("/api/iot/devices/{deviceId}/bindings", bindTestDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.bindingType").value("LOCATION"))
            .andExpect(jsonPath("$.data.bindingId").exists())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @Order(22)
    void binding_history() throws Exception {
        mockMvc.perform(get("/api/iot/devices/{deviceId}/bindings", bindTestDeviceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(23)
    void unbind() throws Exception {
        mockMvc.perform(delete("/api/iot/devices/{deviceId}/bindings/current", bindTestDeviceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(24)
    void unbind_not_bound() throws Exception {
        // 创建一个没有绑定的设备
        DeviceRegisterRequest regReq = new DeviceRegisterRequest();
        regReq.setModelId(Long.valueOf(testModelId));
        regReq.setSerialNo("SN-NOBIND-" + System.currentTimeMillis());
        regReq.setDeviceName("无绑定设备");
        regReq.setMqttClientId("client-nobind-" + System.currentTimeMillis());

        MvcResult regResult = mockMvc.perform(post("/api/iot/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)))
            .andExpect(status().isCreated())
            .andReturn();

        String deviceId = objectMapper.readTree(regResult.getResponse().getContentAsString())
            .get("data").get("deviceId").asText();

        mockMvc.perform(delete("/api/iot/devices/{deviceId}/bindings/current", deviceId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(212007));
    }

    // ==================== 型号删除（需要无关联设备） ====================

    @Test
    @Order(90)
    void model_delete_with_instances() throws Exception {
        // testModelId 有关联设备，不能删除
        mockMvc.perform(delete("/api/iot/device-models/{modelId}", testModelId))
            .andExpect(status().isConflict());
    }

    @Test
    @Order(91)
    void model_delete_success() throws Exception {
        // 创建一个没有关联设备的型号
        DeviceModelCreateRequest req = new DeviceModelCreateRequest();
        req.setModelCode("DEL-TEST-" + System.currentTimeMillis());
        req.setManufacturer("DelCorp");
        req.setDeviceType("RADAR");
        req.setParserCode("simulator");
        req.setHeartbeatTimeoutSeconds(15);

        MvcResult result = mockMvc.perform(post("/api/iot/device-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();

        String delModelId = objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("id").asText();

        mockMvc.perform(delete("/api/iot/device-models/{modelId}", delModelId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }
}
