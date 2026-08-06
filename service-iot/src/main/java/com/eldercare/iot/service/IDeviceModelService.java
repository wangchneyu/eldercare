package com.eldercare.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eldercare.iot.dto.request.DeviceModelCreateRequest;
import com.eldercare.iot.dto.request.DeviceModelUpdateRequest;
import com.eldercare.iot.dto.vo.DeviceModelVO;

/**
 * 设备型号服务接口
 */
public interface IDeviceModelService {

    /**
     * 创建设备型号，校验 modelCode 唯一性。
     *
     * @param request 创建请求
     * @return 新建设备型号 VO
     */
    DeviceModelVO create(DeviceModelCreateRequest request);

    /**
     * 分页查询设备型号列表。
     *
     * @param manufacturer 厂商（可选）
     * @param deviceType   设备类型（可选）
     * @param page         页码
     * @param size         每页条数
     * @return 分页结果
     */
    IPage<DeviceModelVO> list(String manufacturer, String deviceType, int pageNo, int pageSize);

    /**
     * 更新设备型号，使用乐观锁防止并发冲突。
     *
     * @param modelId 设备型号 ID
     * @param request 更新请求
     * @return 更新后设备型号 VO
     */
    DeviceModelVO update(Long modelId, DeviceModelUpdateRequest request);

    /**
     * 删除设备型号；若存在关联设备实例则拒绝删除。
     *
     * @param modelId 设备型号 ID
     */
    void delete(Long modelId);
}
