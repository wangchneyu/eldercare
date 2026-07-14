package com.eldercare.security.aop;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.security.annotation.RequireRole;
import com.eldercare.security.context.SecurityContextHolder;
import com.eldercare.security.domain.LoginUser;
import com.eldercare.security.domain.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;

/**
 * 角色权限 AOP 切面
 * <p>
 * 拦截所有标注了 {@link RequireRole} 注解的方法，验证当前用户是否拥有所需角色
 */
@Slf4j
@Aspect
public class RequireRoleAspect {

    @Pointcut("@annotation(com.eldercare.security.annotation.RequireRole)")
    public void requireRolePointcut() {
    }

    @Around("requireRolePointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取注解信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        UserRole[] requiredRoles = requireRole.value();

        // 获取当前登录用户
        LoginUser loginUser = SecurityContextHolder.getLoginUser();
        if (loginUser == null) {
            log.warn("权限校验失败：未登录用户访问受保护方法 {}.{}",
                    method.getDeclaringClass().getSimpleName(), method.getName());
            throw new BizException(SystemErrorCode.UNAUTHORIZED);
        }

        // 验证角色
        if (!loginUser.hasAnyRole(requiredRoles)) {
            log.warn("权限校验失败：用户 {} (角色: {}) 无权访问 {}.{}，要求角色: {}",
                    loginUser.getUsername(), loginUser.getRoles(),
                    method.getDeclaringClass().getSimpleName(), method.getName(),
                    (Object) requiredRoles);
            throw new BizException(SystemErrorCode.FORBIDDEN);
        }

        log.debug("权限校验通过：用户 {} 访问 {}.{}",
                loginUser.getUsername(),
                method.getDeclaringClass().getSimpleName(), method.getName());

        return joinPoint.proceed();
    }
}
