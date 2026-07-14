package com.eldercare.security.filter;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.security.constant.SecurityConstants;
import com.eldercare.security.context.SecurityContextHolder;
import com.eldercare.security.domain.LoginUser;
import com.eldercare.security.jwt.JwtTokenProvider;
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
import java.util.List;

/**
 * JWT 鉴权过滤器
 * <p>
 * 在请求到达控制器之前验证 Token 有效性，提取用户信息设置到 SecurityContextHolder。
 * 对白名单路径直接放行，对无效 Token 返回 401 错误响应。
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

        // 白名单路径直接放行
        if (isWhitelisted(requestUri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 提取 Token
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            writeErrorResponse(response, "缺少认证令牌");
            return;
        }

        // 验证 Token
        if (!jwtTokenProvider.validateToken(token)) {
            writeErrorResponse(response, "认证令牌无效或已过期");
            return;
        }

        // 提取用户信息并设置上下文
        try {
            LoginUser loginUser = jwtTokenProvider.getLoginUser(token);
            SecurityContextHolder.setLoginUser(loginUser);
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束后清理，防止内存泄漏
            SecurityContextHolder.clear();
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
     * 写出 JSON 格式的 401 错误响应
     */
    private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        R<Void> result = R.fail(SystemErrorCode.UNAUTHORIZED.getCode(), message);
        String json = objectMapper.writeValueAsString(result);
        response.getWriter().write(json);
    }
}
