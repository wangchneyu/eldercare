package com.eldercare.iot.service;

import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.heartbeat.HeartbeatManager;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.eldercare.iot.parser.model.ParsedHeartbeat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceSnapshotServiceTest {

    @Mock
    IotDeviceInstanceMapper instanceMapper;
    @Mock
    IotDeviceModelMapper modelMapper;
    @Mock
    IotDeviceBindingMapper bindingMapper;

    @Test
    void snapshotCombinesRealtimeStatusAndActiveElderLocationBindings() {
        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setDeviceId("DEV-001");
        instance.setModelId(10L);
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        when(instanceMapper.selectOne(any())).thenReturn(instance);

        IotDeviceModel model = new IotDeviceModel();
        model.setId(10L);
        model.setDeviceType("RADAR");
        when(modelMapper.selectById(10L)).thenReturn(model);

        IotDeviceBinding elder = new IotDeviceBinding();
        elder.setBindingId("B-ELDER");
        elder.setBindingType("ELDER");
        elder.setElderId(1001L);
        elder.setParkId("P001");
        elder.setActiveFrom(OffsetDateTime.now().minusMinutes(1));
        IotDeviceBinding location = new IotDeviceBinding();
        location.setBindingId("B-LOCATION");
        location.setBindingType("LOCATION");
        location.setLocationId("LOC-01");
        location.setLocationName("一层活动区");
        when(bindingMapper.selectList(any())).thenReturn(List.of(elder, location));

        HeartbeatManager heartbeatManager = new HeartbeatManager();
        heartbeatManager.recordHeartbeat(new ParsedHeartbeat(
                "EVT", "MSG", "DEV-001", OffsetDateTime.now(), "trace", "P001", "RADAR"), 15);
        DeviceServiceImpl service = new DeviceServiceImpl(
                instanceMapper, modelMapper, bindingMapper, heartbeatManager);

        var snapshot = service.getSnapshot("DEV-001");

        assertEquals("DEV-001", snapshot.getDeviceId());
        assertEquals("RADAR", snapshot.getDeviceType());
        assertEquals("ONLINE", snapshot.getOnlineStatus());
        assertEquals("ACTIVE", snapshot.getLifecycleStatus());
        assertEquals(2, snapshot.getBindings().size());
        assertEquals("1001", snapshot.getBindings().get(0).getElderId());
        assertEquals("LOC-01", snapshot.getBindings().get(1).getLocationId());
        assertNotNull(snapshot.getSnapshotTime());
        assertNotNull(snapshot.getLastHeartbeatAt());
    }
}
