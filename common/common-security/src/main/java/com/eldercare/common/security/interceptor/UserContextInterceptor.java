package com.eldercare.common.security.interceptor;

import com.eldercare.common.security.context.UserContext;
import com.eldercare.common.security.context.UserInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Collections;

public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userIdStr = request.getHeader("X-User-Id");
        String username = request.getHeader("X-User-Name");
        String rolesStr = request.getHeader("X-User-Roles");

        if (StringUtils.hasText(userIdStr)) {
            UserInfo userInfo = new UserInfo();
            try {
                userInfo.setId(Long.valueOf(userIdStr));
            } catch (NumberFormatException e) {
                // ignore invalid format
            }
            userInfo.setUsername(username);
            
            if (StringUtils.hasText(rolesStr)) {
                userInfo.setRoles(Arrays.asList(rolesStr.split(",")));
            } else {
                userInfo.setRoles(Collections.emptyList());
            }

            UserContext.setUser(userInfo);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 必须清理，防止在线程池环境下导致内存泄漏和数据污染
        UserContext.clear();
    }
}
