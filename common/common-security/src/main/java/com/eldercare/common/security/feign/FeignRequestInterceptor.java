package com.eldercare.common.security.feign;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.common.security.context.UserContext;
import com.eldercare.common.security.context.UserInfo;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class FeignRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 1. 传递 TraceId 链路追踪
        String traceId = TraceContext.currentTraceId();
        if (traceId != null) {
            template.header("X-Trace-Id", traceId);
        }

        // 2. 传递 JWT Token (Authorization)
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String authorization = request.getHeader("Authorization");
            if (authorization != null) {
                template.header("Authorization", authorization);
            }
        }

        // 3. 传递当前操作人上下文信息给下游微服务
        UserInfo userInfo = UserContext.getUser();
        if (userInfo != null) {
            if (userInfo.getId() != null) {
                template.header("X-User-Id", String.valueOf(userInfo.getId()));
            }
            if (userInfo.getUsername() != null) {
                template.header("X-User-Name", userInfo.getUsername());
            }
            if (userInfo.getRoles() != null && !userInfo.getRoles().isEmpty()) {
                template.header("X-User-Roles", String.join(",", userInfo.getRoles()));
            }
        }
    }
}
