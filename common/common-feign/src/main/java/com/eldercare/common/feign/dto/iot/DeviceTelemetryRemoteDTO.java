package com.eldercare.common.feign.dto.iot;

import lombok.Data;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

@Data
public class DeviceTelemetryRemoteDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String deviceId;
    private String deviceType;
    private Map<String, Object> properties;
    private Instant reportTime;
}
