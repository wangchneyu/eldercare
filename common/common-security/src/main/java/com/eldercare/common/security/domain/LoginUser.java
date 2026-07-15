package com.eldercare.common.security.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * 当前登录用户信息，存储在 SecurityContextHolder 中
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 角色集合 */
    private Set<UserRole> roles;

    /**
     * 是否拥有指定角色
     */
    public boolean hasRole(UserRole role) {
        return roles != null && roles.contains(role);
    }

    /**
     * 是否拥有指定角色中的任意一个
     */
    public boolean hasAnyRole(UserRole... requiredRoles) {
        if (roles == null || requiredRoles == null) {
            return false;
        }
        for (UserRole role : requiredRoles) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
