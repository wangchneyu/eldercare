package com.eldercare.iot.service;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.iot.dto.request.DeviceLifecycleRequest;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.heartbeat.HeartbeatManager;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceLifecycleTest {

    @Mock
    IotDeviceInstanceMapper instanceMapper;
    @Mock
    IotDeviceModelMapper modelMapper;
    @Mock
    IotDeviceBindingMapper bindingMapper;
    @Mock
    HeartbeatManager heartbeatManager;

    @BeforeEach
    void initializeMybatisLambdaMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), IotDeviceInstance.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), IotDeviceBinding.class);
    }

    @Test
    void sosButtonCannotActivateWithoutCompleteLocation() {
        IotDeviceInstance instance = sosButton("DISABLED", 0);
        when(instanceMapper.selectOne(any())).thenReturn(instance);
        when(modelMapper.selectById(10L)).thenReturn(sosModel());
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        BizException exception = assertThrows(BizException.class,
                () -> service().changeLifecycle("DEV-SOS", lifecycle("ACTIVE", 0)));

        assertEquals(IotErrorCode.DEVICE_BINDING_INVALID, exception.getErrorCode());
        verify(instanceMapper, never()).update(any(), any());
    }

    @Test
    void sosButtonActivatesWhenCompleteLocationExists() {
        IotDeviceInstance current = sosButton("DISABLED", 0);
        IotDeviceInstance updated = sosButton("ACTIVE", 1);
        when(instanceMapper.selectOne(any())).thenReturn(current, updated);
        when(modelMapper.selectById(10L)).thenReturn(sosModel());
        when(bindingMapper.selectList(any())).thenReturn(List.of(locationBinding()));
        when(instanceMapper.update(any(), any())).thenReturn(1);

        var result = service().changeLifecycle("DEV-SOS", lifecycle("ACTIVE", 0));

        assertEquals("ACTIVE", result.getLifecycleStatus());
        assertEquals(1, result.getVersion());
    }

    @Test
    void staleLifecycleVersionIsRejectedBeforeMutation() {
        when(instanceMapper.selectOne(any())).thenReturn(sosButton("DISABLED", 1));

        BizException exception = assertThrows(BizException.class,
                () -> service().changeLifecycle("DEV-SOS", lifecycle("ACTIVE", 0)));

        assertEquals(IotErrorCode.DEVICE_VERSION_CONFLICT, exception.getErrorCode());
        verify(instanceMapper, never()).update(any(), any());
    }

    private DeviceServiceImpl service() {
        return new DeviceServiceImpl(instanceMapper, modelMapper, bindingMapper, heartbeatManager);
    }

    private IotDeviceInstance sosButton(String lifecycleStatus, int version) {
        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setDeviceId("DEV-SOS");
        instance.setModelId(10L);
        instance.setLifecycleStatus(lifecycleStatus);
        instance.setVersion(version);
        return instance;
    }

    private IotDeviceModel sosModel() {
        IotDeviceModel model = new IotDeviceModel();
        model.setId(10L);
        model.setDeviceType("SOS_BUTTON");
        return model;
    }

    private IotDeviceBinding locationBinding() {
        IotDeviceBinding binding = new IotDeviceBinding();
        binding.setParkId("P001");
        binding.setLocationId("LOC-001");
        binding.setLocationType("PUBLIC_AREA");
        binding.setLocationName("Activity Room");
        return binding;
    }

    private DeviceLifecycleRequest lifecycle(String targetStatus, int version) {
        DeviceLifecycleRequest request = new DeviceLifecycleRequest();
        request.setTargetStatus(targetStatus);
        request.setVersion(version);
        return request;
    }
}
