package com.eldercare.operation.controller;

import com.eldercare.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探针 — 联调验证 Gateway 鉴权 + RBAC + 路由全链路
 */
@RestController
public class HealthController {

    /**
     * 匹配 Gateway 受保护路径 /admin/users，
     * 验证 JWT 鉴权 → RBAC 拦截 → 路由转发全链路。
     */
    @GetMapping("/users")
    public R<String> users() {
        return R.ok("operation-service is alive");
    }

    /**
     * 通用探针：直接验证 Gateway → service-operation 链路
     */
    @GetMapping("/actuator/ping")
    public R<String> ping() {
        return R.ok("pong from service-operation :8087");
    }
}
