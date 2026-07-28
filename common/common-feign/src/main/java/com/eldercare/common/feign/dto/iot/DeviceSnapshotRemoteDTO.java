package com.eldercare.common.feign.dto.iot;

import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * service-iot 暴露给内部 Feign 调用方的设备状态与绑定快照。
 */
@Data
public class DeviceSnapshotRemoteDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceId;
    private String deviceType;
    private String onlineStatus;
    private String lifecycleStatus;
    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime snapshotTime;
    private List<DeviceBindingSnapshotRemoteDTO> bindings;
}
