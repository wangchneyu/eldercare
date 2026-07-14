package com.eldercare.security.annotation;

import com.eldercare.security.domain.UserRole;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限注解，声明方法所需的角色
 * <p>
 * 使用示例：
 * <pre>{@code
 * @RequireRole(UserRole.ADMIN)
 * public R<Void> deleteUser(Long id) { ... }
 *
 * @RequireRole({UserRole.ADMIN, UserRole.CAREGIVER})
 * public R<Void> manageElderly(Long id) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    /**
     * 允许访问的角色列表，满足任意一个即可
     */
    UserRole[] value();
}
