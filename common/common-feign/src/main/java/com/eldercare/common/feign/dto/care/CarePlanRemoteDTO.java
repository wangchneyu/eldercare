package com.eldercare.common.feign.dto.care;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDate;

@Data
public class CarePlanRemoteDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long elderId;
    private Long caregiverId;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
}
