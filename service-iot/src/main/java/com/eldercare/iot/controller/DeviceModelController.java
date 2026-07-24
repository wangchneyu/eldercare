package com.eldercare.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eldercare.common.core.domain.R;
import com.eldercare.iot.dto.request.DeviceModelCreateRequest;
import com.eldercare.iot.dto.request.DeviceModelUpdateRequest;
import com.eldercare.iot.dto.vo.DeviceModelVO;
import com.eldercare.iot.service.IDeviceModelService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 设备型号管理
 */
@RestController
@RequestMapping("/api/iot/device-models")
public class DeviceModelController {

    private final IDeviceModelService deviceModelService;

    public DeviceModelController(IDeviceModelService deviceModelService) {
        this.deviceModelService = deviceModelService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<DeviceModelVO> create(@Valid @RequestBody DeviceModelCreateRequest request) {
        DeviceModelVO vo = deviceModelService.create(request);
        return R.ok(vo);
    }

    @GetMapping
    public R<IPage<DeviceModelVO>> list(
            @RequestParam(required = false) String manufacturer,
            @RequestParam(required = false) String deviceType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size > 100) {
            size = 100;
        }
        IPage<DeviceModelVO> result = deviceModelService.list(manufacturer, deviceType, page, size);
        return R.ok(result);
    }

    @PutMapping("/{modelId}")
    public R<DeviceModelVO> update(@PathVariable Long modelId,
                                   @Valid @RequestBody DeviceModelUpdateRequest request) {
        DeviceModelVO vo = deviceModelService.update(modelId, request);
        return R.ok(vo);
    }

    @DeleteMapping("/{modelId}")
    public R<Void> delete(@PathVariable Long modelId) {
        deviceModelService.delete(modelId);
        return R.ok(null);
    }
}
