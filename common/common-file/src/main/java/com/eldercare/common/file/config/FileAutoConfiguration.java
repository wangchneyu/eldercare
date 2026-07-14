package com.eldercare.common.file.config;

import com.eldercare.common.file.service.FileStorageService;
import com.eldercare.common.file.service.impl.LocalFileStorageServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.eldercare.common.file.controller.MockFileController;

@Configuration
@EnableConfigurationProperties(FileProperties.class)
@Import(MockFileController.class)
public class FileAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "eldercare.file", name = "type", havingValue = "local", matchIfMissing = true)
    public FileStorageService localFileStorageService(FileProperties fileProperties) {
        return new LocalFileStorageServiceImpl(fileProperties);
    }
}
