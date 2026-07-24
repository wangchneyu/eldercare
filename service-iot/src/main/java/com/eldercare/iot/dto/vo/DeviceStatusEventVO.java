package com.eldercare.iot.dto.vo;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class DeviceStatusEventVO {
    private String eventType;
    private String oldStatus;
    private String newStatus;
    private OffsetDateTime occurredAt;
}
