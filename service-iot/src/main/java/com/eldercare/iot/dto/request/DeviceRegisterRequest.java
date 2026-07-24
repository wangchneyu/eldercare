package com.eldercare.iot.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DeviceRegisterRequest {

    @NotNull(message = "型号 ID 不能为空")
    private Long modelId;

    @NotBlank(message = "序列号不能为空")
    @Size(max = 128)
    private String serialNo;

    @Size(max = 128)
    private String deviceName;

    @NotBlank(message = "MQTT Client ID 不能为空")
    @Size(max = 128)
    private String mqttClientId;
}
