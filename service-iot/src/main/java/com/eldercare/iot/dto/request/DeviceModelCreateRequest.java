package com.eldercare.iot.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DeviceModelCreateRequest {

    @NotBlank(message = "型号编码不能为空")
    @Size(max = 64)
    private String modelCode;

    @NotBlank(message = "厂商不能为空")
    @Size(max = 128)
    private String manufacturer;

    @NotBlank(message = "设备类型不能为空")
    private String deviceType;

    @NotBlank(message = "解析器标识不能为空")
    @Size(max = 64)
    private String parserCode;

    @NotNull(message = "心跳超时秒数不能为空")
    @Min(value = 5, message = "心跳超时最小 5 秒")
    @Max(value = 300, message = "心跳超时最大 300 秒")
    private Integer heartbeatTimeoutSeconds;

    @Size(max = 256)
    private String description;
}
