package com.eldercare.common.notify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class WeChatRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "OpenId不能为空")
    private String touser;

    @NotBlank(message = "模板ID不能为空")
    private String templateId;

    private String page;

    private Map<String, String> data;
}
