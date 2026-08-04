package com.eldercare.iot.service;

import com.eldercare.iot.dto.request.DeviceBindRequest;
import com.eldercare.iot.dto.vo.DeviceBindingVO;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.enums.BindingStatus;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceBindingServiceTest {

    @Mock
    IotDeviceBindingMapper bindingMapper;
    @Mock
    IotDeviceInstanceMapper deviceMapper;
    @Mock
    IotDeviceModelMapper modelMapper;

    @Test
    void elderBindingIsStoredLocallyWithoutCareServiceCall() {
        IotDeviceInstance device = new IotDeviceInstance();
        device.setDeviceId("DEV-001");
        device.setLifecycleStatus("ACTIVE");
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        DeviceBindRequest request = new DeviceBindRequest();
        request.setBindingType("ELDER");
        request.setElderId(1001L);
        DeviceBindingVO result = service().bind("DEV-001", request);

        ArgumentCaptor<IotDeviceBinding> captor = ArgumentCaptor.forClass(IotDeviceBinding.class);
        verify(bindingMapper).insert(captor.capture());
        assertEquals("ELDER", captor.getValue().getBindingType());
        assertEquals(1001L, captor.getValue().getElderId());
        assertEquals(BindingStatus.ACTIVE.getCode(), captor.getValue().getStatus());
        assertNotNull(result.getBindingId());
    }

    @Test
    void unbindByTypeOnlyUpdatesTheRequestedActiveBinding() {
        IotDeviceBinding elderBinding = new IotDeviceBinding();
        elderBinding.setBindingType("ELDER");
        elderBinding.setStatus(BindingStatus.ACTIVE.getCode());
        when(bindingMapper.selectList(any())).thenReturn(List.of(elderBinding));

        service().unbindByType("DEV-001", "ELDER");

        verify(bindingMapper).updateById(elderBinding);
        assertEquals(BindingStatus.INACTIVE.getCode(), elderBinding.getStatus());
        assertNotNull(elderBinding.getInactiveAt());
    }

    @Test
    void activeBindingsCanBeLookedUpByElder() {
        IotDeviceBinding binding = new IotDeviceBinding();
        binding.setDeviceId("DEV-001");
        binding.setBindingType("ELDER");
        binding.setElderId(1001L);
        binding.setStatus(BindingStatus.ACTIVE.getCode());
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));

        List<DeviceBindingVO> result = service().getActiveByElderId(1001L);

        assertEquals(1, result.size());
        assertEquals("DEV-001", result.get(0).getDeviceId());
        assertEquals("ELDER", result.get(0).getBindingType());
    }

    private DeviceBindingServiceImpl service() {
        return new DeviceBindingServiceImpl(bindingMapper, deviceMapper, modelMapper);
    }
}
