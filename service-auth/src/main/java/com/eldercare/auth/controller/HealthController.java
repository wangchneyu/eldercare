package com.eldercare.auth.controller;

import com.eldercare.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探针 — 联调验证 Gateway 路由链路可达
 */
@RestController
@RequestMapping("/auth")
public class HealthController {

    /**
     * 匹配 Gateway 白名单路径 POST /auth/login 的 GET 版本，
     * 验证白名单放行 + Nacos 服务发现 + 路由转发全链路。
     */
    @GetMapping("/login")
    public R<String> login() {
        return R.ok("auth-service is alive");
    }

    /**
     * 通用探针：直接验证 Gateway → service-auth 链路
     */
    @GetMapping("/actuator/ping")
    public R<String> ping() {
        return R.ok("pong from service-auth :8090");
    }
}
