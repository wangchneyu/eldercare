package com.eldercare.common.security.feign;

import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.context.SecurityContextHolder;
import com.eldercare.common.security.domain.LoginUser;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 远程调用请求拦截器
 * <p>
 * 在微服务间 Feign 调用时，自动传递当前请求的认证信息和链路追踪 ID。
 * 1. 优先从当前线程的 SecurityContextHolder 获取登录用户，并以明文请求头 X-User-* 形式透传给下游。
 * 2. 如果存在原始的 Authorization 请求头，也一并向下游透传。
 * 3. 自动透传 X-Trace-Id 保持链路追踪。
 */
@Slf4j
public class FeignAuthRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 1. 传递链路追踪 TraceId
        String traceId = TraceContext.currentTraceId();
        if (traceId != null && !traceId.isEmpty()) {
            template.header(SecurityConstants.HEADER_TRACE_ID, traceId);
        }

        // 2. 优先从 SecurityContextHolder 提取当前登录用户，并以明文头传递给下游
        LoginUser loginUser = SecurityContextHolder.getLoginUser();
        if (loginUser != null) {
            template.header(SecurityConstants.HEADER_USER_ID, String.valueOf(loginUser.getUserId()));
            template.header(SecurityConstants.HEADER_USERNAME, loginUser.getUsername());
            if (loginUser.getRoles() != null) {
                String rolesStr = String.join(",", loginUser.getRoles().stream().map(Enum::name).toList());
                template.header(SecurityConstants.HEADER_USER_ROLES, rolesStr);
            }
        }

        // 3. 同时透传原始的 Authorization 头（如果存在，作为回退和扩展支持）
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String authHeader = request.getHeader(SecurityConstants.HEADER_AUTHORIZATION);
            if (authHeader != null && !authHeader.isEmpty()) {
                template.header(SecurityConstants.HEADER_AUTHORIZATION, authHeader);
            }
        }
    }
}
