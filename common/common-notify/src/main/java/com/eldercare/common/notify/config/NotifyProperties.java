package com.eldercare.common.notify.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "eldercare.notify")
public class NotifyProperties {
    /**
     * 启用类型: mock | aliyun 等。默认值为 mock。
     */
    private String type = "mock";
}
