package com.eldercare.iot.controller;

import com.eldercare.common.core.domain.R;
import com.eldercare.iot.dto.request.DeviceBindRequest;
import com.eldercare.iot.dto.vo.DeviceBindingVO;
import com.eldercare.iot.service.IDeviceBindingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备绑定管理接口
 */
@RestController
@RequestMapping("/api/iot/devices/{deviceId}/bindings")
@RequiredArgsConstructor
public class DeviceBindingController {

    private final IDeviceBindingService deviceBindingService;

    /**
     * 创建或替换设备绑定
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<DeviceBindingVO> bind(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceBindRequest request) {
        DeviceBindingVO vo = deviceBindingService.bind(deviceId, request);
        return R.ok(vo);
    }

    /**
     * 解绑设备当前所有 ACTIVE 绑定
     */
    @DeleteMapping("/current")
    public R<Void> unbind(@PathVariable String deviceId) {
        deviceBindingService.unbind(deviceId);
        return R.ok(null);
    }

    /**
     * 查询设备绑定历史
     */
    @GetMapping
    public R<List<DeviceBindingVO>> getHistory(@PathVariable String deviceId) {
        List<DeviceBindingVO> list = deviceBindingService.getHistory(deviceId);
        return R.ok(list);
    }
}
