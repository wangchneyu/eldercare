package com.eldercare.security.feign;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.security.constant.SecurityConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 远程调用请求拦截器
 * <p>
 * 在微服务间 Feign 调用时，自动传递当前请求的认证信息（JWT Token）和链路追踪 ID，
 * 确保下游服务能够识别用户身份并保持链路追踪。
 */
@Slf4j
public class FeignAuthRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 传递认证 Token
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String authHeader = request.getHeader(SecurityConstants.HEADER_AUTHORIZATION);
            if (authHeader != null && !authHeader.isEmpty()) {
                template.header(SecurityConstants.HEADER_AUTHORIZATION, authHeader);
            }
        }

        // 传递链路追踪 TraceId
        String traceId = TraceContext.currentTraceId();
        if (traceId != null && !traceId.isEmpty()) {
            template.header(SecurityConstants.HEADER_TRACE_ID, traceId);
        }
    }
}
