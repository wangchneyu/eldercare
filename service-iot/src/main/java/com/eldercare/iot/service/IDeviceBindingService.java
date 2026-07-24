package com.eldercare.iot.service;

import com.eldercare.iot.dto.request.DeviceBindRequest;
import com.eldercare.iot.dto.vo.DeviceBindingVO;

import java.util.List;

/**
 * 设备绑定服务接口
 */
public interface IDeviceBindingService {

    /**
     * 创建或替换设备绑定。
     *
     * @param deviceId 设备 ID
     * @param request  绑定请求
     * @return 新绑定记录
     */
    DeviceBindingVO bind(String deviceId, DeviceBindRequest request);

    /**
     * 解绑设备当前所有 ACTIVE 绑定。
     *
     * @param deviceId 设备 ID
     */
    void unbind(String deviceId);

    /**
     * 查询设备全部绑定历史（按创建时间倒序）。
     *
     * @param deviceId 设备 ID
     * @return 绑定记录列表
     */
    List<DeviceBindingVO> getHistory(String deviceId);
}
