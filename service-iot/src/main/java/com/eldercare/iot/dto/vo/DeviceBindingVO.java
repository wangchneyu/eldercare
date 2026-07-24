package com.eldercare.iot.dto.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class DeviceBindingVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private String bindingId;
    private String deviceId;
    private String bindingType;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long elderId;
    private String parkId;
    private String buildingId;
    private String roomId;
    private String roomNo;
    private String locationId;
    private String locationType;
    private String locationName;
    private String floorId;
    private String status;
    private OffsetDateTime activeFrom;
    private OffsetDateTime inactiveAt;
    private OffsetDateTime createdAt;
}
