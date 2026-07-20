package com.eldercare.common.file.config;

import com.eldercare.common.file.service.IFileStorageService;
import com.eldercare.common.file.service.impl.LocalFileStorageServiceImpl;
import com.eldercare.common.file.service.impl.CosFileStorageServiceImpl;
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
    public IFileStorageService localFileStorageService(FileProperties fileProperties) {
        return new LocalFileStorageServiceImpl(fileProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "eldercare.file", name = "type", havingValue = "cos")
    public IFileStorageService cosFileStorageService(FileProperties fileProperties) {
        return new CosFileStorageServiceImpl(fileProperties);
    }
}
