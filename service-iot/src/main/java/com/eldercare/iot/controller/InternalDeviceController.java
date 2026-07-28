package com.eldercare.iot.controller;

import com.eldercare.common.feign.dto.iot.DeviceSnapshotRemoteDTO;
import com.eldercare.iot.service.IDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供服务间 Feign 调用的设备查询接口，不经 gateway，也不套 R<T>。
 */
@RestController
@RequestMapping("/internal/iot/devices")
@RequiredArgsConstructor
public class InternalDeviceController {

    private final IDeviceService deviceService;

    @GetMapping("/{deviceId}/snapshot")
    public DeviceSnapshotRemoteDTO getSnapshot(@PathVariable String deviceId) {
        return deviceService.getSnapshot(deviceId);
    }
}
