package com.eldercare.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eldercare.common.core.domain.R;
import com.eldercare.iot.dto.request.DeviceLifecycleRequest;
import com.eldercare.iot.dto.request.DeviceQuery;
import com.eldercare.iot.dto.request.DeviceRegisterRequest;
import com.eldercare.iot.dto.vo.DeviceDetailVO;
import com.eldercare.iot.dto.vo.DeviceStatusEventVO;
import com.eldercare.iot.dto.vo.DeviceStatusVO;
import com.eldercare.iot.dto.vo.DeviceVO;

import java.util.List;
import com.eldercare.iot.service.IDeviceService;
import com.eldercare.iot.support.IdempotencyService;
import com.eldercare.iot.support.IotAuditLogger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 设备实例 REST 控制器
 */
@RestController
@RequestMapping("/api/iot/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final IDeviceService deviceService;
    private final IdempotencyService idempotencyService;
    private final IotAuditLogger auditLogger;

    /**
     * 注册设备
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<DeviceVO> register(@Valid @RequestBody DeviceRegisterRequest request,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                ServerHttpRequest httpRequest) {
        DeviceVO device = idempotencyService.execute("device-register", idempotencyKey, request, DeviceVO.class, () -> {
            DeviceVO created = deviceService.register(request);
            auditLogger.success("DEVICE_REGISTER", created.getDeviceId(), httpRequest);
            return created;
        });
        return R.ok(device);
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
                                       @Valid @RequestBody DeviceLifecycleRequest request,
                                       ServerHttpRequest httpRequest) {
        DeviceVO device = deviceService.changeLifecycle(deviceId, request);
        auditLogger.success("DEVICE_LIFECYCLE_CHANGE", deviceId, httpRequest);
        return R.ok(device);
    }

    /**
     * 获取设备在线状态快照
     */
    @GetMapping("/{deviceId}/status")
    public R<DeviceStatusVO> getStatus(@PathVariable String deviceId) {
        return R.ok(deviceService.getStatus(deviceId));
    }

    /**
     * 获取当前进程内记录的设备在线状态事件。
     */
    @GetMapping("/{deviceId}/status-events")
    public R<List<DeviceStatusEventVO>> getStatusEvents(@PathVariable String deviceId) {
        return R.ok(deviceService.getStatusEvents(deviceId));
    }
}
