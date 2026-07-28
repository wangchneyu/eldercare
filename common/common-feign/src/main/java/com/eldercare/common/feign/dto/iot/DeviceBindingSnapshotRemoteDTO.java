package com.eldercare.common.feign.dto.iot;

import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 设备当前绑定的跨服务只读快照。
 */
@Data
public class DeviceBindingSnapshotRemoteDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String bindingId;
    private String bindingType;
    private String elderId;
    private String parkId;
    private String buildingId;
    private String roomId;
    private String roomNo;
    private String locationId;
    private String locationType;
    private String locationName;
    private String floorId;
    private OffsetDateTime activeFrom;
}
