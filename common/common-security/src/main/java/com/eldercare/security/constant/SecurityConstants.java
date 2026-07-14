package com.eldercare.security.constant;

/**
 * 安全模块常量定义
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** Token 类型前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** HTTP 请求头 — 认证信息 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** HTTP 请求头 — 链路追踪 ID */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** JWT Claims 键 — 用户ID */
    public static final String CLAIM_USER_ID = "userId";

    /** JWT Claims 键 — 用户名 */
    public static final String CLAIM_USERNAME = "username";

    /** JWT Claims 键 — 角色列表（逗号分隔） */
    public static final String CLAIM_ROLES = "roles";

    /** JWT Claims 键 — Token 类型（access / refresh） */
    public static final String CLAIM_TOKEN_TYPE = "tokenType";

    /** Token 类型 — 访问令牌 */
    public static final String TOKEN_TYPE_ACCESS = "access";

    /** Token 类型 — 刷新令牌 */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /** 默认放行路径（登录接口） */
    public static final String[] DEFAULT_WHITELIST = {
            "/auth/login",
            "/auth/register",
            "/auth/refresh"
    };
}
