package com.eldercare.iot.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DeviceBindRequest {

    @NotBlank(message = "绑定类型不能为空")
    private String bindingType;

    // ELDER 绑定字段
    private Long elderId;
    private String parkId;
    private String buildingId;
    private String roomId;
    private String roomNo;

    // LOCATION 绑定字段
    private String locationId;
    private String locationType;
    private String locationName;
    private String floorId;
}
