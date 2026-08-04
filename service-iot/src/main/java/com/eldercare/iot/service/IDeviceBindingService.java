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
     * 解绑设备指定类型的当前 ACTIVE 绑定。
     *
     * @param deviceId 设备 ID
     * @param bindingType 绑定类型（ELDER / LOCATION）
     */
    void unbindByType(String deviceId, String bindingType);

    /**
     * 查询设备全部绑定历史（按创建时间倒序）。
     *
     * @param deviceId 设备 ID
     * @return 绑定记录列表
     */
    List<DeviceBindingVO> getHistory(String deviceId);

    /**
     * 查询某个长者当前有效的设备绑定，供管理端退住等流程反查。
     *
     * @param elderId 长者 ID
     * @return 当前有效的 ELDER 绑定记录
     */
    List<DeviceBindingVO> getActiveByElderId(Long elderId);
}
