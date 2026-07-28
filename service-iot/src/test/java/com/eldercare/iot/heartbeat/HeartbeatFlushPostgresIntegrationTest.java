package com.eldercare.iot.heartbeat;

import com.eldercare.iot.IntegrationTestConfig;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.parser.model.ParsedHeartbeat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
class HeartbeatFlushPostgresIntegrationTest {

    @Autowired
    IotDeviceInstanceMapper instanceMapper;
    @Autowired
    IotDeviceModelMapper modelMapper;

    private Long deviceRowId;
    private Long modelId;

    @AfterEach
    void cleanup() {
        if (deviceRowId != null) {
            instanceMapper.deleteById(deviceRowId);
        }
        if (modelId != null) {
            modelMapper.deleteById(modelId);
        }
    }

    @Test
    void groupedFlushPersistsOnlineStatusAndLatestHeartbeatToPostgres() {
        String suffix = Long.toString(System.nanoTime());
        IotDeviceModel model = new IotDeviceModel();
        model.setModelCode("HB-MODEL-" + suffix);
        model.setManufacturer("integration-test");
        model.setDeviceType("RADAR");
        model.setParserCode("simulator");
        model.setHeartbeatTimeoutSeconds(15);
        model.setEnabled(true);
        model.setVersion(0);
        model.setCreatedAt(OffsetDateTime.now());
        modelMapper.insert(model);
        modelId = model.getId();

        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setDeviceId("HB-DEV-" + suffix);
        instance.setSerialNo("HB-SN-" + suffix);
        instance.setDeviceName("心跳刷库集成测试设备");
        instance.setModelId(modelId);
        instance.setMqttClientId("hb-client-" + suffix);
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        instance.setCreatedAt(OffsetDateTime.now());
        instanceMapper.insert(instance);
        deviceRowId = instance.getId();

        HeartbeatManager manager = new HeartbeatManager();
        manager.recordHeartbeat(new ParsedHeartbeat(
                "EVT-" + suffix,
                "MSG-" + suffix,
                instance.getDeviceId(),
                OffsetDateTime.now(),
                "trace-" + suffix,
                "P001",
                "RADAR"), 15);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        HeartbeatFlushTask task = new HeartbeatFlushTask(manager, instanceMapper, scheduler, 5, 100);

        assertEquals(1, task.flushPending());
        IotDeviceInstance persisted = instanceMapper.selectById(deviceRowId);
        assertEquals("ONLINE", persisted.getOnlineStatus());
        assertNotNull(persisted.getLastHeartbeatAt());
        assertEquals(0, task.flushPending(), "已确认版本不应在下一轮重复写库");
    }
}
