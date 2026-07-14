package com.eldercare.common.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "eldercare.file")
public class FileProperties {
    /**
     * 启用类型: local | oss 等。默认值为 local。
     */
    private String type = "local";

    private LocalConfig local = new LocalConfig();

    @Data
    public static class LocalConfig {
        /**
         * 本地存储物理路径，默认存储在当前目录下的 uploads/ 目录
         */
        private String uploadPath = "./uploads/";

        /**
         * 预览基础域名，如 http://localhost:8080，本地 Mock 会将其拼接为签名URL的前缀
         */
        private String domain = "http://localhost:8080";
    }
}
