package com.eldercare.iot.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DeviceLifecycleRequest {

    @NotBlank(message = "目标状态不能为空")
    private String targetStatus;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}
