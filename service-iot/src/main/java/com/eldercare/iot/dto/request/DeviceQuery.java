package com.eldercare.iot.dto.request;

import lombok.Data;

@Data
public class DeviceQuery {

    private String deviceType;
    private String lifecycleStatus;
    private String onlineStatus;
    private String parkId;
    private Long elderId;
    private Long modelId;
    private String keyword;

    private Integer pageNo = 1;
    private Integer pageSize = 20;
}
