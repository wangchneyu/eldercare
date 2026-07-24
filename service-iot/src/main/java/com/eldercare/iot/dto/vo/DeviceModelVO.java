package com.eldercare.iot.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class DeviceModelVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private String modelCode;
    private String manufacturer;
    private String deviceType;
    private String parserCode;
    private Integer heartbeatTimeoutSeconds;
    private String description;
    private Boolean enabled;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
