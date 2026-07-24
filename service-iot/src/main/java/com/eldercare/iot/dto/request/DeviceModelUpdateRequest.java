package com.eldercare.iot.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DeviceModelUpdateRequest {

    @Min(value = 5, message = "心跳超时最小 5 秒")
    @Max(value = 300, message = "心跳超时最大 300 秒")
    private Integer heartbeatTimeoutSeconds;

    @Size(max = 256)
    private String description;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}
