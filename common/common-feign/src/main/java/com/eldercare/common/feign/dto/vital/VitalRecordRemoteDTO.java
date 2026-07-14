package com.eldercare.common.feign.dto.vital;

import lombok.Data;
import java.io.Serializable;
import java.time.Instant;

@Data
public class VitalRecordRemoteDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long elderId;
    private String vitalType;
    private String value;
    private Instant recordTime;
}
