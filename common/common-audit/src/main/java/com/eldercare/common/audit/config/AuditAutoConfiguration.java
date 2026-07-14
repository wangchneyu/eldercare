package com.eldercare.common.audit.config;

import com.eldercare.common.audit.aspect.AuditLogAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableAspectJAutoProxy
public class AuditAutoConfiguration {

    @Bean
    public AuditLogAspect auditLogAspect() {
        return new AuditLogAspect();
    }
}
