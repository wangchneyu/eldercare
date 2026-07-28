package com.eldercare.iot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.utils.IdUtil;
import com.eldercare.iot.dto.request.DeviceBindRequest;
import com.eldercare.iot.dto.vo.DeviceBindingVO;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.enums.BindingStatus;
import com.eldercare.iot.enums.BindingType;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.enums.LifecycleStatus;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.remote.CareClientCaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设备绑定服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeviceBindingServiceImpl implements IDeviceBindingService {

    private final IotDeviceBindingMapper bindingMapper;
    private final IotDeviceInstanceMapper deviceMapper;
    private final CareClientCaller careClientCaller;

    @Override
    public DeviceBindingVO bind(String deviceId, DeviceBindRequest request) {
        // 1. 加载设备实例，不存在则抛异常
        IotDeviceInstance device = deviceMapper.selectOne(
                new LambdaQueryWrapper<IotDeviceInstance>()
                        .eq(IotDeviceInstance::getDeviceId, deviceId)
        );
        if (device == null) {
            throw new BizException(IotErrorCode.DEVICE_NOT_FOUND);
        }

        // 2. 校验设备生命周期状态必须为 ACTIVE
        if (!LifecycleStatus.ACTIVE.getCode().equals(device.getLifecycleStatus())) {
            throw new BizException(IotErrorCode.DEVICE_STATUS_NOT_ALLOWED);
        }

        // 3. 校验 bindingType 合法性（仅允许 ELDER / LOCATION）
        BindingType bindingType;
        try {
            bindingType = BindingType.fromCode(request.getBindingType());
        } catch (IllegalArgumentException e) {
            throw new BizException(IotErrorCode.DEVICE_BINDING_INVALID);
        }

        // 4/5. 按类型填充绑定字段
        IotDeviceBinding newBinding = new IotDeviceBinding();
        newBinding.setDeviceId(deviceId);
        newBinding.setBindingType(bindingType.getCode());

        if (bindingType == BindingType.ELDER) {
            careClientCaller.validateActiveElder(request.getElderId());
            newBinding.setElderId(request.getElderId());
            newBinding.setParkId(request.getParkId());
            newBinding.setBuildingId(request.getBuildingId());
            newBinding.setRoomId(request.getRoomId());
            newBinding.setRoomNo(request.getRoomNo());
        } else {
            // LOCATION 绑定
            newBinding.setLocationId(request.getLocationId());
            newBinding.setLocationType(request.getLocationType());
            newBinding.setLocationName(request.getLocationName());
            newBinding.setFloorId(request.getFloorId());
        }

        // 6. 若已存在同类型 ACTIVE 绑定，先将其置为 INACTIVE
        OffsetDateTime now = OffsetDateTime.now();
        List<IotDeviceBinding> existingActive = bindingMapper.selectList(
                new LambdaQueryWrapper<IotDeviceBinding>()
                        .eq(IotDeviceBinding::getDeviceId, deviceId)
                        .eq(IotDeviceBinding::getBindingType, bindingType.getCode())
                        .eq(IotDeviceBinding::getStatus, BindingStatus.ACTIVE.getCode())
        );
        for (IotDeviceBinding old : existingActive) {
            old.setStatus(BindingStatus.INACTIVE.getCode());
            old.setInactiveAt(now);
            bindingMapper.updateById(old);
        }

        // 7. 创建新绑定记录
        newBinding.setBindingId(IdUtil.uuid32());
        newBinding.setStatus(BindingStatus.ACTIVE.getCode());
        newBinding.setActiveFrom(now);
        newBinding.setCreatedAt(now);
        bindingMapper.insert(newBinding);

        // 8. 返回 VO
        return toVO(newBinding);
    }

    @Override
    public void unbind(String deviceId) {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. 查询该设备所有 ACTIVE 绑定
        List<IotDeviceBinding> activeBindings = bindingMapper.selectList(
                new LambdaQueryWrapper<IotDeviceBinding>()
                        .eq(IotDeviceBinding::getDeviceId, deviceId)
                        .eq(IotDeviceBinding::getStatus, BindingStatus.ACTIVE.getCode())
        );

        // 2. 无任何 ACTIVE 绑定则抛异常
        if (activeBindings.isEmpty()) {
            throw new BizException(IotErrorCode.DEVICE_NOT_BOUND);
        }

        // 3. 批量置为 INACTIVE
        for (IotDeviceBinding binding : activeBindings) {
            binding.setStatus(BindingStatus.INACTIVE.getCode());
            binding.setInactiveAt(now);
            bindingMapper.updateById(binding);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceBindingVO> getHistory(String deviceId) {
        List<IotDeviceBinding> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<IotDeviceBinding>()
                        .eq(IotDeviceBinding::getDeviceId, deviceId)
                        .orderByDesc(IotDeviceBinding::getCreatedAt)
        );
        return bindings.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * Entity → VO 转换
     */
    private DeviceBindingVO toVO(IotDeviceBinding entity) {
        DeviceBindingVO vo = new DeviceBindingVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
