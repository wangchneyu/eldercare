package com.eldercare.iot.controller;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.BizException;
import com.eldercare.iot.dto.vo.DeviceBindingVO;
import com.eldercare.iot.enums.BindingStatus;
import com.eldercare.iot.enums.IotErrorCode;
import com.eldercare.iot.service.IDeviceBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面向管理端的绑定反查接口。
 */
@RestController
@RequestMapping("/iot/bindings")
@RequiredArgsConstructor
public class DeviceBindingLookupController {

    private final IDeviceBindingService deviceBindingService;

    @GetMapping
    public R<List<DeviceBindingVO>> getActiveByElderId(
            @RequestParam Long elderId,
            @RequestParam(defaultValue = "ACTIVE") String status) {
        if (!BindingStatus.ACTIVE.getCode().equals(status)) {
            throw new BizException(IotErrorCode.DEVICE_BINDING_INVALID);
        }
        return R.ok(deviceBindingService.getActiveByElderId(elderId));
    }
}
