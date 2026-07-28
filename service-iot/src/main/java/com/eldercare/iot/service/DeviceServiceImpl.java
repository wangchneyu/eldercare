package com.eldercare.iot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.utils.IdUtil;
import com.eldercare.iot.dto.request.DeviceLifecycleRequest;
import com.eldercare.iot.dto.request.DeviceQuery;
import com.eldercare.iot.dto.request.DeviceRegisterRequest;
import com.eldercare.iot.dto.vo.DeviceBindingVO;
import com.eldercare.iot.dto.vo.DeviceDetailVO;
import com.eldercare.iot.dto.vo.DeviceStatusEventVO;
import com.eldercare.iot.dto.vo.DeviceStatusVO;
import com.eldercare.iot.dto.vo.DeviceVO;
import com.eldercare.iot.entity.IotDeviceBinding;
import com.eldercare.iot.entity.IotDeviceInstance;
import com.eldercare.iot.entity.IotDeviceModel;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.heartbeat.HeartbeatManager;
import com.eldercare.iot.heartbeat.HeartbeatState;
import com.eldercare.iot.mapper.IotDeviceBindingMapper;
import com.eldercare.iot.mapper.IotDeviceInstanceMapper;
import com.eldercare.iot.mapper.IotDeviceModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 设备实例服务实现
 */
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements IDeviceService {

    private final IotDeviceInstanceMapper instanceMapper;
    private final IotDeviceModelMapper modelMapper;
    private final IotDeviceBindingMapper bindingMapper;
    private final HeartbeatManager heartbeatManager;

    // ==================== register ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceVO register(DeviceRegisterRequest request) {
        // 1. 校验型号存在
        IotDeviceModel model = modelMapper.selectById(request.getModelId());
        if (model == null) {
            throw new BizException(IotErrorCode.DEVICE_NOT_FOUND);
        }

        // 2. 生成 deviceId
        String deviceId = IdUtil.uuid32();

        // 3. 校验 mqttClientId 唯一
        LambdaQueryWrapper<IotDeviceInstance> mqttCheck = new LambdaQueryWrapper<>();
        mqttCheck.eq(IotDeviceInstance::getMqttClientId, request.getMqttClientId());
        if (instanceMapper.selectCount(mqttCheck) > 0) {
            throw new BizException(IotErrorCode.DEVICE_ALREADY_REGISTERED);
        }

        // 4. 校验 modelId + serialNo 唯一
        LambdaQueryWrapper<IotDeviceInstance> serialCheck = new LambdaQueryWrapper<>();
        serialCheck.eq(IotDeviceInstance::getModelId, request.getModelId())
                   .eq(IotDeviceInstance::getSerialNo, request.getSerialNo());
        if (instanceMapper.selectCount(serialCheck) > 0) {
            throw new BizException(IotErrorCode.DEVICE_ALREADY_REGISTERED);
        }

        // 5. 构建实体并入库
        IotDeviceInstance instance = new IotDeviceInstance();
        instance.setDeviceId(deviceId);
        instance.setSerialNo(request.getSerialNo());
        instance.setDeviceName(request.getDeviceName());
        instance.setModelId(request.getModelId());
        instance.setMqttClientId(request.getMqttClientId());
        instance.setLifecycleStatus("ACTIVE");
        instance.setOnlineStatus("UNKNOWN");
        instance.setCreatedAt(OffsetDateTime.now());
        instance.setUpdatedAt(OffsetDateTime.now());

        instanceMapper.insert(instance);

        return toVO(instance, model);
    }

    // ==================== list ====================

    @Override
    public IPage<DeviceVO> list(DeviceQuery query) {
        // --- 按 deviceType 反查型号 ID 集合 ---
        Set<Long> modelIdFilter = null;
        if (StringUtils.hasText(query.getDeviceType())) {
            LambdaQueryWrapper<IotDeviceModel> modelQuery = new LambdaQueryWrapper<>();
            modelQuery.eq(IotDeviceModel::getDeviceType, query.getDeviceType());
            List<IotDeviceModel> models = modelMapper.selectList(modelQuery);
            modelIdFilter = models.stream()
                                  .map(IotDeviceModel::getId)
                                  .collect(Collectors.toSet());
            if (modelIdFilter.isEmpty()) {
                return emptyPage(query);
            }
        }

        // --- 按 parkId / elderId 反查设备 ID 集合（来自绑定表） ---
        Set<String> deviceIdFilter = null;
        if (StringUtils.hasText(query.getParkId()) || query.getElderId() != null) {
            LambdaQueryWrapper<IotDeviceBinding> bindingQuery = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(query.getParkId())) {
                bindingQuery.eq(IotDeviceBinding::getParkId, query.getParkId());
            }
            if (query.getElderId() != null) {
                bindingQuery.eq(IotDeviceBinding::getElderId, query.getElderId());
            }
            bindingQuery.eq(IotDeviceBinding::getStatus, "ACTIVE");
            List<IotDeviceBinding> bindings = bindingMapper.selectList(bindingQuery);
            deviceIdFilter = bindings.stream()
                                     .map(IotDeviceBinding::getDeviceId)
                                     .collect(Collectors.toSet());
            if (deviceIdFilter.isEmpty()) {
                return emptyPage(query);
            }
        }

        // --- 构建实例查询条件 ---
        LambdaQueryWrapper<IotDeviceInstance> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(query.getLifecycleStatus())) {
            wrapper.eq(IotDeviceInstance::getLifecycleStatus, query.getLifecycleStatus());
        }
        if (StringUtils.hasText(query.getOnlineStatus())) {
            wrapper.eq(IotDeviceInstance::getOnlineStatus, query.getOnlineStatus());
        }
        if (query.getModelId() != null) {
            wrapper.eq(IotDeviceInstance::getModelId, query.getModelId());
        }
        if (modelIdFilter != null) {
            wrapper.in(IotDeviceInstance::getModelId, modelIdFilter);
        }
        if (deviceIdFilter != null) {
            wrapper.in(IotDeviceInstance::getDeviceId, deviceIdFilter);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w
                    .like(IotDeviceInstance::getDeviceName, kw)
                    .or().like(IotDeviceInstance::getSerialNo, kw)
                    .or().like(IotDeviceInstance::getDeviceId, kw));
        }

        wrapper.orderByDesc(IotDeviceInstance::getCreatedAt);

        IPage<IotDeviceInstance> page = instanceMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        // --- 批量加载型号信息用于 VO 填充 ---
        Set<Long> modelIds = page.getRecords().stream()
                .map(IotDeviceInstance::getModelId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, IotDeviceModel> modelMap = new HashMap<>();
        if (!modelIds.isEmpty()) {
            modelMapper.selectBatchIds(modelIds)
                       .forEach(m -> modelMap.put(m.getId(), m));
        }

        // --- 转换为 VO 分页结果 ---
        IPage<DeviceVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<DeviceVO> voList = page.getRecords().stream()
                .map(inst -> toVO(inst, modelMap.get(inst.getModelId())))
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return voPage;
    }

    // ==================== getDetail ====================

    @Override
    public DeviceDetailVO getDetail(String deviceId) {
        IotDeviceInstance instance = findByDeviceId(deviceId);
        IotDeviceModel model = modelMapper.selectById(instance.getModelId());

        // 查询当前 ACTIVE 绑定
        LambdaQueryWrapper<IotDeviceBinding> bindingQuery = new LambdaQueryWrapper<>();
        bindingQuery.eq(IotDeviceBinding::getDeviceId, deviceId)
                    .eq(IotDeviceBinding::getStatus, "ACTIVE");
        List<IotDeviceBinding> bindings = bindingMapper.selectList(bindingQuery);

        // 组装 DeviceDetailVO
        DeviceDetailVO vo = new DeviceDetailVO();
        BeanUtils.copyProperties(instance, vo);

        if (model != null) {
            vo.setModelCode(model.getModelCode());
            vo.setDeviceType(model.getDeviceType());
            vo.setManufacturer(model.getManufacturer());
            vo.setHeartbeatTimeoutSeconds(model.getHeartbeatTimeoutSeconds());
        }

        List<DeviceBindingVO> bindingVOs = bindings.stream().map(b -> {
            DeviceBindingVO bvo = new DeviceBindingVO();
            BeanUtils.copyProperties(b, bvo);
            return bvo;
        }).collect(Collectors.toList());
        vo.setCurrentBindings(bindingVOs);

        return vo;
    }

    // ==================== changeLifecycle ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceVO changeLifecycle(String deviceId, DeviceLifecycleRequest request) {
        IotDeviceInstance instance = findByDeviceId(deviceId);

        String current = instance.getLifecycleStatus();
        String target = request.getTargetStatus();

        // 校验状态转换规则
        validateTransition(current, target);

        // 乐观锁：以当前 lifecycleStatus 作为条件，防止并发覆盖
        LambdaUpdateWrapper<IotDeviceInstance> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(IotDeviceInstance::getDeviceId, deviceId)
                     .eq(IotDeviceInstance::getLifecycleStatus, current)
                     .set(IotDeviceInstance::getLifecycleStatus, target)
                     .set(IotDeviceInstance::getUpdatedAt, OffsetDateTime.now());

        int rows = instanceMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new BizException(IotErrorCode.DEVICE_STATUS_NOT_ALLOWED);
        }

        // 重新加载并返回
        IotDeviceInstance updated = findByDeviceId(deviceId);
        IotDeviceModel model = modelMapper.selectById(updated.getModelId());
        return toVO(updated, model);
    }

    // ==================== getStatus ====================

    @Override
    public DeviceStatusVO getStatus(String deviceId) {
        IotDeviceInstance instance = findByDeviceId(deviceId);

        Optional<HeartbeatState> currentState = heartbeatManager.getState(deviceId);
        DeviceStatusVO vo = new DeviceStatusVO();
        vo.setDeviceId(instance.getDeviceId());
        vo.setOnlineStatus(currentState.map(state -> state.onlineStatus().getCode()).orElse(instance.getOnlineStatus()));
        vo.setLastHeartbeat(currentState.map(HeartbeatState::lastHeartbeatAt).orElse(instance.getLastHeartbeatAt()));
        vo.setSnapshotTime(OffsetDateTime.now());
        return vo;
    }

    @Override
    public List<DeviceStatusEventVO> getStatusEvents(String deviceId) {
        findByDeviceId(deviceId);
        return heartbeatManager.recentEvents(deviceId).stream().map(event -> {
            DeviceStatusEventVO vo = new DeviceStatusEventVO();
            vo.setEventType(event.eventType());
            vo.setOldStatus(event.oldStatus().getCode());
            vo.setNewStatus(event.newStatus().getCode());
            vo.setOccurredAt(event.occurredAt());
            return vo;
        }).toList();
    }

    // ==================== Private helpers ====================

    /**
     * 根据 deviceId 查询设备实例，不存在则抛异常
     */
    private IotDeviceInstance findByDeviceId(String deviceId) {
        LambdaQueryWrapper<IotDeviceInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotDeviceInstance::getDeviceId, deviceId);
        IotDeviceInstance instance = instanceMapper.selectOne(wrapper);
        if (instance == null) {
            throw new BizException(IotErrorCode.DEVICE_NOT_FOUND);
        }
        return instance;
    }

    /**
     * 校验生命周期状态转换是否合法
     * <p>
     * 合法转换：
     *   ACTIVE   → DISABLED / RETIRED
     *   DISABLED → RETIRED
     *   RETIRED  → （不可逆，不允许任何转换）
     */
    private void validateTransition(String current, String target) {
        boolean allowed = false;
        if ("ACTIVE".equals(current)) {
            allowed = "DISABLED".equals(target) || "RETIRED".equals(target);
        } else if ("DISABLED".equals(current)) {
            allowed = "RETIRED".equals(target);
        }
        // RETIRED → 任何状态均不允许
        if (!allowed) {
            throw new BizException(IotErrorCode.DEVICE_STATUS_NOT_ALLOWED);
        }
    }

    /**
     * 实体转 VO，同时填充型号冗余字段
     */
    private DeviceVO toVO(IotDeviceInstance instance, IotDeviceModel model) {
        DeviceVO vo = new DeviceVO();
        BeanUtils.copyProperties(instance, vo);
        if (model != null) {
            vo.setModelCode(model.getModelCode());
            vo.setDeviceType(model.getDeviceType());
        }
        return vo;
    }

    /**
     * 返回空分页结果
     */
    private IPage<DeviceVO> emptyPage(DeviceQuery query) {
        return new Page<>(query.getPage(), query.getSize(), 0);
    }
}
