package com.eldercare.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eldercare.common.core.domain.R;
import com.eldercare.iot.dto.request.DeviceLifecycleRequest;
import com.eldercare.iot.dto.request.DeviceQuery;
import com.eldercare.iot.dto.request.DeviceRegisterRequest;
import com.eldercare.iot.dto.vo.DeviceDetailVO;
import com.eldercare.iot.dto.vo.DeviceStatusVO;
import com.eldercare.iot.dto.vo.DeviceVO;
import com.eldercare.iot.service.IDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 设备实例 REST 控制器
 */
@RestController
@RequestMapping("/api/iot/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final IDeviceService deviceService;

    /**
     * 注册设备
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<DeviceVO> register(@Valid @RequestBody DeviceRegisterRequest request) {
        return R.ok(deviceService.register(request));
    }

    /**
     * 分页查询设备列表
     */
    @GetMapping
    public R<IPage<DeviceVO>> list(@ModelAttribute DeviceQuery query) {
        return R.ok(deviceService.list(query));
    }

    /**
     * 获取设备详情（含型号信息与当前绑定）
     */
    @GetMapping("/{deviceId}")
    public R<DeviceDetailVO> getDetail(@PathVariable String deviceId) {
        return R.ok(deviceService.getDetail(deviceId));
    }

    /**
     * 设备生命周期状态变更
     */
    @PatchMapping("/{deviceId}/lifecycle-status")
    public R<DeviceVO> changeLifecycle(@PathVariable String deviceId,
                                       @Valid @RequestBody DeviceLifecycleRequest request) {
        return R.ok(deviceService.changeLifecycle(deviceId, request));
    }

    /**
     * 获取设备在线状态快照
     */
    @GetMapping("/{deviceId}/status")
    public R<DeviceStatusVO> getStatus(@PathVariable String deviceId) {
        return R.ok(deviceService.getStatus(deviceId));
    }
}
