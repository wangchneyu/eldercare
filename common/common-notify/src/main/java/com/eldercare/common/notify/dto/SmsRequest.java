package com.eldercare.common.notify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class SmsRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotBlank(message = "模板ID不能为空")
    private String templateId;

    private Map<String, String> params;
}
