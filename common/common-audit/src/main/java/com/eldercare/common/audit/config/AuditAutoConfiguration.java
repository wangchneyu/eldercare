package com.eldercare.common.audit.config;

import com.eldercare.common.audit.aspect.AuditLogAspect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableAspectJAutoProxy
public class AuditAutoConfiguration {

    @Bean
    public AuditLogAspect auditLogAspect(ObjectMapper objectMapper) {
        return new AuditLogAspect(objectMapper);
    }
}
