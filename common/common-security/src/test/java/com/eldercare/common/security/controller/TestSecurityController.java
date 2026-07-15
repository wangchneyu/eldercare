package com.eldercare.common.security.controller;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.security.annotation.RequireRole;
import com.eldercare.common.security.context.SecurityContextHolder;
import com.eldercare.common.security.domain.LoginUser;
import com.eldercare.common.security.domain.UserRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试专用 Controller，验证安全模块的鉴权和权限控制
 */
@RestController
@RequestMapping("/test")
public class TestSecurityController {

    /**
     * 公开接口 — 无需鉴权（在白名单中）
     */
    @GetMapping("/public")
    public R<String> publicEndpoint() {
        return R.ok("public data");
    }

    /**
     * 仅需登录 — 任何已认证用户均可访问
     */
    @GetMapping("/auth-only")
    public R<String> authOnlyEndpoint() {
        LoginUser user = SecurityContextHolder.getLoginUser();
        return R.ok("hello " + user.getUsername());
    }

    /**
     * 仅管理员 — 需要 ADMIN 角色
     */
    @RequireRole(UserRole.ADMIN)
    @GetMapping("/admin-only")
    public R<String> adminOnlyEndpoint() {
        return R.ok("admin resource");
    }

    /**
     * 仅看护人员 — 需要 CAREGIVER 角色
     */
    @RequireRole(UserRole.CAREGIVER)
    @GetMapping("/caregiver")
    public R<String> caregiverEndpoint() {
        return R.ok("caregiver resource");
    }

    /**
     * 管理员或运营人员 — 多角色均可访问
     */
    @RequireRole({UserRole.ADMIN, UserRole.OPERATOR})
    @GetMapping("/admin-or-operator")
    public R<String> adminOrOperatorEndpoint() {
        return R.ok("admin or operator resource");
    }
}
