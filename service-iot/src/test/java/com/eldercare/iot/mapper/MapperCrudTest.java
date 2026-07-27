package com.eldercare.iot.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotMqOutbox;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段二验证测试 — Mapper CRUD + 乐观锁 + JSONB
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MapperCrudTest {

    @Autowired
    private IotDeviceModelMapper modelMapper;

    @Autowired
    private IotDeviceInstanceMapper instanceMapper;

    @Autowired
    private IotDeviceBindingMapper bindingMapper;

    @Autowired
    private IotMqOutboxMapper outboxMapper;

    // 用于跨测试方法传递 ID
    private static Long testModelId;
    private static Long testInstanceId;
    private static Long testBindingId;
    private static Long testOutboxId;

    // ==================== 1. IotDeviceModel CRUD ====================

    @Test
    @Order(1)
    void model_insert() {
        IotDeviceModel model = new IotDeviceModel();
        model.setModelCode("TEST-MODEL-" + System.currentTimeMillis());
        model.setManufacturer("TestMfg");
        model.setDeviceType("MATTRESS");
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(30);
        model.setDescription("测试型号");
        model.setEnabled(true);
        model.setVersion(0);
        model.setCreatedAt(OffsetDateTime.now());

        int rows = modelMapper.insert(model);
        assertEquals(1, rows);
        assertNotNull(model.getId());
        testModelId = model.getId();
    }

    @Test
    @Order(2)
    void model_selectById() {
        IotDeviceModel model = modelMapper.selectById(testModelId);
        assertNotNull(model);
        assertEquals("TestMfg", model.getManufacturer());
        assertEquals("MATTRESS", model.getDeviceType());
        assertEquals(30, model.getHeartbeatTimeoutSeconds());
    }

    @Test
    @Order(3)
    void model_updateById() {
        IotDeviceModel model = modelMapper.selectById(testModelId);
        model.setHeartbeatTimeoutSeconds(60);
        model.setUpdatedAt(OffsetDateTime.now());

        int rows = modelMapper.updateById(model);
        assertEquals(1, rows);

        IotDeviceModel updated = modelMapper.selectById(testModelId);
        assertEquals(60, updated.getHeartbeatTimeoutSeconds());
    }

    @Test
    @Order(4)
    void model_deleteById() {
        int rows = modelMapper.deleteById(testModelId);
        assertEquals(1, rows);
        assertNull(modelMapper.selectById(testModelId));
    }

    // ==================== 2. IotDeviceInstance CRUD ====================

    @Test
    @Order(10)
    void instance_insert() {
        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setDeviceId("TEST-DEV-" + System.currentTimeMillis());
        instance.setSerialNo("SN-TEST-" + System.currentTimeMillis());
        instance.setDeviceName("测试设备");
        instance.setModelId(1L);
        instance.setMqttClientId("test-client-" + System.currentTimeMillis());
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        instance.setCreatedAt(OffsetDateTime.now());

        int rows = instanceMapper.insert(instance);
        assertEquals(1, rows);
        assertNotNull(instance.getId());
        testInstanceId = instance.getId();
    }

    @Test
    @Order(11)
    void instance_selectById() {
        IotDeviceInstance instance = instanceMapper.selectById(testInstanceId);
        assertNotNull(instance);
        assertEquals("测试设备", instance.getDeviceName());
        assertEquals("ACTIVE", instance.getLifecycleStatus());
    }

    @Test
    @Order(12)
    void instance_updateById() {
        IotDeviceInstance instance = instanceMapper.selectById(testInstanceId);
        instance.setOnlineStatus("ONLINE");
        instance.setLastHeartbeatAt(OffsetDateTime.now());

        int rows = instanceMapper.updateById(instance);
        assertEquals(1, rows);

        IotDeviceInstance updated = instanceMapper.selectById(testInstanceId);
        assertEquals("ONLINE", updated.getOnlineStatus());
    }

    @Test
    @Order(13)
    void instance_deleteById() {
        int rows = instanceMapper.deleteById(testInstanceId);
        assertEquals(1, rows);
        assertNull(instanceMapper.selectById(testInstanceId));
    }

    // ==================== 3. IotDeviceBinding CRUD ====================

    @Test
    @Order(20)
    void binding_insert() {
        IotDeviceBinding binding = new IotDeviceBinding();
        binding.setBindingId("BIND-" + System.currentTimeMillis());
        binding.setDeviceId("TEST-DEV-BIND");
        binding.setBindingType("LOCATION");
        binding.setParkId("P001");
        binding.setLocationId("LOC-001");
        binding.setLocationType("PUBLIC_AREA");
        binding.setLocationName("测试公共区域");
        binding.setStatus("ACTIVE");
        binding.setActiveFrom(OffsetDateTime.now());
        binding.setCreatedAt(OffsetDateTime.now());

        int rows = bindingMapper.insert(binding);
        assertEquals(1, rows);
        assertNotNull(binding.getId());
        testBindingId = binding.getId();
    }

    @Test
    @Order(21)
    void binding_selectById() {
        IotDeviceBinding binding = bindingMapper.selectById(testBindingId);
        assertNotNull(binding);
        assertEquals("LOCATION", binding.getBindingType());
        assertEquals("测试公共区域", binding.getLocationName());
    }

    @Test
    @Order(22)
    void binding_updateById() {
        IotDeviceBinding binding = bindingMapper.selectById(testBindingId);
        binding.setStatus("INACTIVE");
        binding.setInactiveAt(OffsetDateTime.now());

        int rows = bindingMapper.updateById(binding);
        assertEquals(1, rows);

        IotDeviceBinding updated = bindingMapper.selectById(testBindingId);
        assertEquals("INACTIVE", updated.getStatus());
    }

    @Test
    @Order(23)
    void binding_deleteById() {
        int rows = bindingMapper.deleteById(testBindingId);
        assertEquals(1, rows);
        assertNull(bindingMapper.selectById(testBindingId));
    }

    // ==================== 4. IotMqOutbox CRUD + JSONB ====================

    @Test
    @Order(30)
    void outbox_insert_with_jsonb() {
        IotMqOutbox outbox = new IotMqOutbox();
        outbox.setEventId("EVT-" + System.currentTimeMillis());
        outbox.setDeviceId("DEV-SOS-001");
        outbox.setSourceMessageId("MSG-" + System.currentTimeMillis());
        outbox.setEventType("SOS_TRIGGERED");
        outbox.setTopic("elder-sos-event");
        outbox.setTag("SOS");

        // JSONB payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "DEV-SOS-001");
        payload.put("deviceType", "SOS_BUTTON");
        payload.put("triggerType", "BUTTON_PRESS");
        payload.put("batteryLevel", 85);
        payload.put("location", Map.of(
                "locationId", "LOC-001",
                "locationType", "PUBLIC_AREA",
                "locationName", "三楼活动区"
        ));
        outbox.setPayload(payload);
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(OffsetDateTime.now());

        int rows = outboxMapper.insert(outbox);
        assertEquals(1, rows);
        assertNotNull(outbox.getId());
        testOutboxId = outbox.getId();
    }

    @Test
    @Order(31)
    void outbox_selectById_jsonb_consistency() {
        IotMqOutbox outbox = outboxMapper.selectById(testOutboxId);
        assertNotNull(outbox);

        // 验证 JSONB payload 写入读出一致
        Map<String, Object> payload = outbox.getPayload();
        assertNotNull(payload);
        assertEquals("DEV-SOS-001", payload.get("deviceId"));
        assertEquals("SOS_BUTTON", payload.get("deviceType"));
        assertEquals("BUTTON_PRESS", payload.get("triggerType"));
        assertEquals(85, payload.get("batteryLevel"));

        // 验证嵌套对象
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) payload.get("location");
        assertNotNull(location);
        assertEquals("LOC-001", location.get("locationId"));
        assertEquals("三楼活动区", location.get("locationName"));
    }

    @Test
    @Order(32)
    void outbox_update_status() {
        IotMqOutbox outbox = outboxMapper.selectById(testOutboxId);
        outbox.setStatus("SENT");
        outbox.setSentAt(OffsetDateTime.now());

        int rows = outboxMapper.updateById(outbox);
        assertEquals(1, rows);

        IotMqOutbox updated = outboxMapper.selectById(testOutboxId);
        assertEquals("SENT", updated.getStatus());
        assertNotNull(updated.getSentAt());
    }

    @Test
    @Order(33)
    void outbox_deleteById() {
        int rows = outboxMapper.deleteById(testOutboxId);
        assertEquals(1, rows);
        assertNull(outboxMapper.selectById(testOutboxId));
    }

    // ==================== 5. 乐观锁测试 ====================

    @Test
    @Order(40)
    void model_optimistic_lock() {
        // 插入测试数据
        IotDeviceModel model = new IotDeviceModel();
        model.setModelCode("LOCK-TEST-" + System.currentTimeMillis());
        model.setManufacturer("LockMfg");
        model.setDeviceType("RADAR");
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(15);
        model.setEnabled(true);
        model.setVersion(0);
        model.setCreatedAt(OffsetDateTime.now());
        modelMapper.insert(model);

        Long lockTestId = model.getId();

        // 第一次 update 成功
        IotDeviceModel model1 = modelMapper.selectById(lockTestId);
        model1.setHeartbeatTimeoutSeconds(20);
        int rows1 = modelMapper.updateById(model1);
        assertEquals(1, rows1);

        // 第二次用旧 version update，应该失败（返回 0 行）
        IotDeviceModel model2 = new IotDeviceModel();
        model2.setId(lockTestId);
        model2.setVersion(0); // 旧版本号
        model2.setHeartbeatTimeoutSeconds(25);
        int rows2 = modelMapper.updateById(model2);
        assertEquals(0, rows2, "乐观锁应该阻止旧版本更新");

        // 清理
        modelMapper.deleteById(lockTestId);
    }
}
