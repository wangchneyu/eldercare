package com.eldercare.iot.service;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.iot.dto.request.DeviceBindRequest;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.remote.CareClientCaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceBindingCareValidationTest {

    @Mock
    IotDeviceBindingMapper bindingMapper;
    @Mock
    IotDeviceInstanceMapper deviceMapper;
    @Mock
    CareClientCaller careClientCaller;

    @Test
    void unavailableCareServiceRejectsElderBindingBeforeDatabaseMutation() {
        IotDeviceInstance device = activeDevice();
        when(deviceMapper.selectOne(any())).thenReturn(device);
        doThrow(new BizException(SystemErrorCode.REMOTE_CALL_FAILED))
                .when(careClientCaller).validateActiveElder(1001L);
        DeviceBindingServiceImpl service = new DeviceBindingServiceImpl(
                bindingMapper, deviceMapper, careClientCaller);

        BizException exception = assertThrows(BizException.class,
                () -> service.bind("DEV-001", elderRequest()));

        assertEquals(100007, exception.getErrorCode().getCode());
        verifyNoBindingMutation();
    }

    @Test
    void missingElderRejectsBindingBeforeDatabaseMutation() {
        IotDeviceInstance device = activeDevice();
        when(deviceMapper.selectOne(any())).thenReturn(device);
        doThrow(new BizException(IotErrorCode.ELDER_NOT_FOUND))
                .when(careClientCaller).validateActiveElder(1001L);
        DeviceBindingServiceImpl service = new DeviceBindingServiceImpl(
                bindingMapper, deviceMapper, careClientCaller);

        BizException exception = assertThrows(BizException.class,
                () -> service.bind("DEV-001", elderRequest()));

        assertEquals(212015, exception.getErrorCode().getCode());
        verifyNoBindingMutation();
    }

    private IotDeviceInstance activeDevice() {
        IotDeviceInstance device = new IotDeviceInstance();
        device.setDeviceId("DEV-001");
        device.setLifecycleStatus("ACTIVE");
        return device;
    }

    private void verifyNoBindingMutation() {
        verify(bindingMapper, never()).insert(any(IotDeviceBinding.class));
        verify(bindingMapper, never()).updateById(any(IotDeviceBinding.class));
    }

    private DeviceBindRequest elderRequest() {
        DeviceBindRequest request = new DeviceBindRequest();
        request.setBindingType("ELDER");
        request.setElderId(1001L);
        return request;
    }
}
