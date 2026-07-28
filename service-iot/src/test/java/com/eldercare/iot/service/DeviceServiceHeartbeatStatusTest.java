package com.eldercare.iot.service;

import com.eldercare.iot.entity.IotDeviceInstance;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceHeartbeatStatusTest {

    @Mock
    IotDeviceInstanceMapper instanceMapper;
    @Mock
    IotDeviceModelMapper modelMapper;
    @Mock
    IotDeviceBindingMapper bindingMapper;

    @Test
    void statusQueryPrefersCurrentMemorySnapshotOverDelayedDatabaseSnapshot() {
        IotDeviceInstance persisted = new IotDeviceInstance();
        persisted.setDeviceId("DEV-001");
        persisted.setOnlineStatus("UNKNOWN");
        when(instanceMapper.selectOne(any())).thenReturn(persisted);
        HeartbeatManager manager = new HeartbeatManager();
        manager.recordHeartbeat(new ParsedHeartbeat(
                "EVT", "MSG", "DEV-001", OffsetDateTime.now(), "trace", "P001", "RADAR"), 15);
        DeviceServiceImpl service = new DeviceServiceImpl(instanceMapper, modelMapper, bindingMapper, manager);

        var status = service.getStatus("DEV-001");

        assertEquals("ONLINE", status.getOnlineStatus());
        assertNotNull(status.getLastHeartbeat());
        assertNotNull(status.getSnapshotTime());
        assertEquals(1, service.getStatusEvents("DEV-001").size());
        assertEquals("ONLINE", service.getStatusEvents("DEV-001").get(0).getEventType());
    }
}
