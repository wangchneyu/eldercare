package com.eldercare.common.security.context;

import com.eldercare.common.security.domain.LoginUser;

/**
 * 安全上下文持有者，基于 ThreadLocal 存储当前请求的用户信息
 * <p>
 * 设计风格与 common-core 中的 TraceContext 保持一致
 */
public final class SecurityContextHolder {

    private SecurityContextHolder() {
    }

    private static final ThreadLocal<LoginUser> CONTEXT = new ThreadLocal<>();

    /**
     * 获取当前线程的登录用户信息
     *
     * @return 登录用户，未认证时返回 null
     */
    public static LoginUser getLoginUser() {
        return CONTEXT.get();
    }

    /**
     * 设置当前线程的登录用户信息
     *
     * @param loginUser 登录用户
     */
    public static void setLoginUser(LoginUser loginUser) {
        CONTEXT.set(loginUser);
    }

    /**
     * 清除当前线程的用户信息，防止内存泄漏
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 获取当前用户ID
     *
     * @return 用户ID，未认证时返回 null
     */
    public static Long getCurrentUserId() {
        LoginUser user = CONTEXT.get();
        return user != null ? user.getUserId() : null;
    }
}
