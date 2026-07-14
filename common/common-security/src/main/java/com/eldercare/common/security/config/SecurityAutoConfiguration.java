package com.eldercare.common.security.config;

import com.eldercare.common.security.feign.FeignRequestInterceptor;
import com.eldercare.common.security.interceptor.UserContextInterceptor;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityAutoConfiguration {

    /**
     * 注册 Feign 传递安全上下文的拦截器
     */
    @Bean
    public RequestInterceptor feignRequestInterceptor() {
        return new FeignRequestInterceptor();
    }

    /**
     * 当处于 Web 应用程序环境下时，注册当前用户登录上下文拦截器
     */
    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public static class WebMvcSecurityConfiguration implements WebMvcConfigurer {
        
        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new UserContextInterceptor())
                    .addPathPatterns("/**")
                    .order(-100); // 赋予较高优先级，确保在业务拦截器前提取好上下文
        }
    }
}
