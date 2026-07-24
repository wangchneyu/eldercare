package com.eldercare.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eldercare.iot.dto.request.DeviceLifecycleRequest;
import com.eldercare.iot.dto.request.DeviceQuery;
import com.eldercare.iot.dto.request.DeviceRegisterRequest;
import com.eldercare.iot.dto.vo.DeviceDetailVO;
import com.eldercare.iot.dto.vo.DeviceStatusVO;
import com.eldercare.iot.dto.vo.DeviceVO;

/**
 * 设备实例服务接口
 */
public interface IDeviceService {

    /**
     * 注册设备：生成 deviceId（UUID），校验型号存在性与唯一约束
     */
    DeviceVO register(DeviceRegisterRequest request);

    /**
     * 分页查询设备列表，支持多条件过滤
     */
    IPage<DeviceVO> list(DeviceQuery query);

    /**
     * 获取设备详情，包含型号信息和当前有效绑定
     */
    DeviceDetailVO getDetail(String deviceId);

    /**
     * 设备生命周期状态变更（带状态校验与乐观锁）
     */
    DeviceVO changeLifecycle(String deviceId, DeviceLifecycleRequest request);

    /**
     * 获取设备在线状态快照
     */
    DeviceStatusVO getStatus(String deviceId);
}
