package com.eldercare.iot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eldercare.common.core.exception.BizException;
import com.eldercare.iot.dto.request.DeviceModelCreateRequest;
import com.eldercare.iot.dto.request.DeviceModelUpdateRequest;
import com.eldercare.iot.dto.vo.DeviceModelVO;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

/**
 * 设备型号服务实现
 */
@Service
public class DeviceModelServiceImpl implements IDeviceModelService {

    private final IotDeviceModelMapper deviceModelMapper;
    private final IotDeviceInstanceMapper deviceInstanceMapper;

    public DeviceModelServiceImpl(IotDeviceModelMapper deviceModelMapper,
                                  IotDeviceInstanceMapper deviceInstanceMapper) {
        this.deviceModelMapper = deviceModelMapper;
        this.deviceInstanceMapper = deviceInstanceMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceModelVO create(DeviceModelCreateRequest request) {
        // 校验 modelCode 唯一性
        LambdaQueryWrapper<IotDeviceModel> qw = new LambdaQueryWrapper<>();
        qw.eq(IotDeviceModel::getModelCode, request.getModelCode());
        Long count = deviceModelMapper.selectCount(qw);
        if (count != null && count > 0) {
            throw new BizException(IotErrorCode.DEVICE_MODEL_CODE_DUPLICATE);
        }

        // 构建实体
        IotDeviceModel entity = new IotDeviceModel();
        BeanUtils.copyProperties(request, entity);
        entity.setEnabled(true);
        entity.setVersion(0);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        deviceModelMapper.insert(entity);
        return toVO(entity);
    }

    @Override
    public IPage<DeviceModelVO> list(String manufacturer, String deviceType, int pageNo, int pageSize) {
        LambdaQueryWrapper<IotDeviceModel> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(manufacturer)) {
            qw.like(IotDeviceModel::getManufacturer, manufacturer);
        }
        if (StringUtils.hasText(deviceType)) {
            qw.eq(IotDeviceModel::getDeviceType, deviceType);
        }
        qw.orderByDesc(IotDeviceModel::getCreatedAt);

        Page<IotDeviceModel> pageParam = new Page<>(pageNo, pageSize);
        IPage<IotDeviceModel> entityPage = deviceModelMapper.selectPage(pageParam, qw);

        // 转换为 VO 分页
        Page<DeviceModelVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceModelVO update(Long modelId, DeviceModelUpdateRequest request) {
        IotDeviceModel entity = deviceModelMapper.selectById(modelId);
        if (entity == null) {
            throw new BizException(IotErrorCode.DEVICE_MODEL_NOT_FOUND);
        }

        // 设置版本号，由 MyBatis-Plus @Version 插件处理乐观锁
        entity.setVersion(request.getVersion());
        if (request.getHeartbeatTimeoutSeconds() != null) {
            entity.setHeartbeatTimeoutSeconds(request.getHeartbeatTimeoutSeconds());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        entity.setUpdatedAt(OffsetDateTime.now());

        int rows = deviceModelMapper.updateById(entity);
        if (rows == 0) {
            throw new BizException(IotErrorCode.DEVICE_MODEL_VERSION_CONFLICT);
        }

        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long modelId) {
        IotDeviceModel entity = deviceModelMapper.selectById(modelId);
        if (entity == null) {
            throw new BizException(IotErrorCode.DEVICE_MODEL_NOT_FOUND);
        }

        // 检查是否有关联设备实例
        LambdaQueryWrapper<IotDeviceInstance> qw = new LambdaQueryWrapper<>();
        qw.eq(IotDeviceInstance::getModelId, modelId);
        Long instanceCount = deviceInstanceMapper.selectCount(qw);
        if (instanceCount != null && instanceCount > 0) {
            throw new BizException(IotErrorCode.DEVICE_MODEL_IN_USE);
        }

        deviceModelMapper.deleteById(modelId);
    }

    /**
     * 实体转 VO
     */
    private DeviceModelVO toVO(IotDeviceModel entity) {
        DeviceModelVO vo = new DeviceModelVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
