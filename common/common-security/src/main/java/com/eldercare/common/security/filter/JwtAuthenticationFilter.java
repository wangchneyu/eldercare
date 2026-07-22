package com.eldercare.common.security.filter;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.core.utils.TraceContext;
import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.context.SecurityContextHolder;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import com.eldercare.common.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全认证过滤器
 * <p>
 * 在请求到达控制器之前提取用户信息设置到 SecurityContextHolder。
 * 支持两种模式：
 * 1. 优先读取网关透传的明文请求头 X-User-Id / X-User-Roles，解析后直接写入上下文（高性能）。
 * 2. 回退机制：若无透传请求头，则从 Authorization 头中提取 Token 进行本地 JWT 签名校验并解析（适合独立运行或单元测试）。
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final List<String> whitelist;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   List<String> whitelist,
                                   ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.whitelist = whitelist != null ? whitelist : Collections.emptyList();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestUri = request.getRequestURI();

        // 从网关透传的 X-Trace-Id 头提取 traceId 并设置到 MDC，确保全链路可追踪
        String traceId = request.getHeader("X-Trace-Id");
        if (StringUtils.hasText(traceId)) {
            TraceContext.setTraceId(traceId);
        }

        try {
            // 白名单路径直接放行
            if (isWhitelisted(requestUri)) {
                filterChain.doFilter(request, response);
                return;
            }

            // 1. 优先从 Header 提取网关透传的用户信息
            String userIdStr = request.getHeader(SecurityConstants.HEADER_USER_ID);
            String username = request.getHeader(SecurityConstants.HEADER_USERNAME);
            String rolesStr = request.getHeader(SecurityConstants.HEADER_USER_ROLES);

            if (StringUtils.hasText(userIdStr)) {
                try {
                    Long userId = Long.valueOf(userIdStr);
                    Set<UserRole> roles = new HashSet<>();
                    if (StringUtils.hasText(rolesStr)) {
                        for (String role : rolesStr.split(",")) {
                            try {
                                roles.add(UserRole.valueOf(role.trim()));
                            } catch (IllegalArgumentException e) {
                                log.warn("未知角色类型被丢弃: {}", role);
                            }
                        }
                    }
                    LoginUser loginUser = new LoginUser(userId, username, roles);
                    SecurityContextHolder.setLoginUser(loginUser);
                    log.debug("[SecurityFilter] 从网关透传请求头提取用户身份成功: userId={}", userId);
                    filterChain.doFilter(request, response);
                    return;
                } catch (NumberFormatException e) {
                    log.warn("[SecurityFilter] 提取网关用户ID格式错误: {}", userIdStr);
                } finally {
                    SecurityContextHolder.clear();
                }
            }

            // 2. 回退模式：本地验证并解析 JWT
            String token = extractToken(request);
            if (!StringUtils.hasText(token)) {
                writeErrorResponse(response);
                return;
            }

            if (!jwtTokenProvider.validateToken(token)) {
                writeErrorResponse(response);
                return;
            }

            try {
                LoginUser loginUser = jwtTokenProvider.getLoginUser(token);
                SecurityContextHolder.setLoginUser(loginUser);
                log.debug("[SecurityFilter] 本地解密 JWT 身份成功: userId={}", loginUser.getUserId());
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clear();
            }
        } finally {
            // 清理 traceId MDC，防止线程池复用导致串号
            TraceContext.clear();
        }
    }

    /**
     * 从请求头提取 Bearer Token
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(SecurityConstants.HEADER_AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            return header.substring(SecurityConstants.TOKEN_PREFIX.length());
        }
        return null;
    }

    /**
     * 判断是否为白名单路径
     */
    private boolean isWhitelisted(String requestUri) {
        for (String pattern : whitelist) {
            if (pathMatcher.match(pattern, requestUri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写出 JSON 格式 of 401 错误响应
     */
    private void writeErrorResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        R<Void> result = R.fail(SystemErrorCode.UNAUTHORIZED);
        String json = objectMapper.writeValueAsString(result);
        response.getWriter().write(json);
    }
}
