package com.eldercare.gateway.filter;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.security.config.SecurityProperties;
import com.eldercare.common.security.constant.SecurityConstants;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * RBAC 角色-路径拦截全局过滤器（WebFlux / Gateway 专用）
 * <p>
 * 在 JWT 鉴权通过后，根据用户角色和请求路径的映射关系进行权限校验。
 * 无权限访问返回 403 Forbidden。
 * <p>
 * 职责:
 * <ol>
 *   <li>白名单路径直接跳过 RBAC 检查</li>
 *   <li>从 exchange attributes 获取 LoginUser（由 JwtAuthGlobalFilter 设置）</li>
 *   <li>匹配请求路径对应的角色要求（从 yml 配置读取）</li>
 *   <li>未配置 RBAC 规则的路径默认放行</li>
 *   <li>角色不匹配返回 403 JSON 错误响应</li>
 * </ol>
 * <p>
 * 执行顺序: Order = -95（JwtAuthGlobalFilter=-100 之后，WebSocketConnectionLimitFilter=-90 之前）
 */
@Slf4j
@Component
public class RbacAuthGlobalFilter implements GlobalFilter, Ordered {

    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RbacAuthGlobalFilter(SecurityProperties securityProperties, ObjectMapper objectMapper) {
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 白名单路径跳过 RBAC 检查（由 JwtAuthGlobalFilter 直接放行）
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        // 2. 从 attributes 获取 LoginUser（由 JwtAuthGlobalFilter 设置）
        LoginUser loginUser = exchange.getAttribute("loginUser");
        if (loginUser == null) {
            // 无用户信息：可能是非认证路径，放行
            return chain.filter(exchange);
        }

        // 3. 匹配路径 → 所需角色映射
        List<SecurityProperties.RoleMappingProperties> roleMappings = securityProperties.getRoleMappings();
        if (roleMappings == null || roleMappings.isEmpty()) {
            // 未配置任何 RBAC 规则：所有已认证用户可访问所有路径
            return chain.filter(exchange);
        }

        Set<UserRole> requiredRoles = findRequiredRoles(path, roleMappings);
        if (requiredRoles == null || requiredRoles.isEmpty()) {
            // 当前路径未在 RBAC 规则中配置：默认放行（向后兼容）
            return chain.filter(exchange);
        }

        // 4. 检查用户是否拥有所需角色
        if (!loginUser.hasAnyRole(requiredRoles.toArray(new UserRole[0]))) {
            log.warn("RBAC 拒绝: 用户 {}(roles={}) 无权访问 {}", loginUser.getUsername(), loginUser.getRoles(), path);
            return writeForbidden(exchange, "无权访问此资源");
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 在 JwtAuthGlobalFilter (-100) 之后，WebSocketConnectionLimitFilter (-90) 之前
        return -95;
    }

    // ==================== 内部方法 ====================

    /**
     * 判断请求路径是否在白名单中
     */
    private boolean isWhitelisted(String path) {
        List<String> whitelist = securityProperties.getWhitelist();
        if (whitelist == null) {
            return false;
        }
        return whitelist.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 在角色-路径映射中查找当前路径所需的角色集合
     *
     * @return 所需角色集合；若未匹配到任何规则返回 null
     */
    private Set<UserRole> findRequiredRoles(String path, List<SecurityProperties.RoleMappingProperties> mappings) {
        for (SecurityProperties.RoleMappingProperties mapping : mappings) {
            if (mapping.getPaths() == null) {
                continue;
            }
            for (String pathPattern : mapping.getPaths()) {
                if (pathMatcher.match(pathPattern, path)) {
                    return mapping.getRoles();
                }
            }
        }
        return null;
    }

    /**
     * 返回 403 JSON 错误响应
     */
    private Mono<Void> writeForbidden(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        R<Void> result = R.fail(SystemErrorCode.FORBIDDEN.getCode(), message);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            log.error("序列化 403 响应失败", e);
            return response.setComplete();
        }
    }
}
