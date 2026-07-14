package com.eldercare.common.feign.dto.alert;

import lombok.Data;
import java.io.Serializable;
import java.time.Instant;

@Data
public class AlertRemoteDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long elderId;
    private String alertLevel;
    private String content;
    private String status;
    private Instant triggerTime;
}
