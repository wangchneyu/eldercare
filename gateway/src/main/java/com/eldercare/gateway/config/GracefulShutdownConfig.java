package com.eldercare.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 网关优雅停机配置
 * <p>
 * 收到 SIGTERM 后的处理流程:
 * <ol>
 *   <li>从 Nacos 注销（停止接收新请求）— 由 Spring Cloud Nacos 自动处理</li>
 *   <li>等待存量 HTTP 请求处理完毕（超时 30s）— 由 server.shutdown=graceful 配置</li>
 *   <li>向所有活跃 WebSocket 连接发送 Close frame（code=1001）— Netty 优雅关闭时自动处理</li>
 *   <li>关闭连接，停止进程</li>
 * </ol>
 * <p>
 * 技术实现说明:
 * <ul>
 *   <li>server.shutdown=graceful 使 Netty 停止接受新连接并等待存量请求完成</li>
 *   <li>spring.lifecycle.timeout-per-shutdown-phase=30s 控制最大等待时间</li>
 *   <li>Netty 在关闭时会自动向活跃 WebSocket 连接发送 Close frame (code=1001, going away)</li>
 *   <li>Nacos 注销先于 Netty 关闭，确保流量不再路由到此实例</li>
 * </ul>
 */
@Slf4j
@Configuration
public class GracefulShutdownConfig implements ApplicationListener<ContextClosedEvent> {

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("网关收到关闭信号，开始优雅停机：" +
                "Nacos 注销 → 等待存量请求完成(30s) → WebSocket Close frame(1001) → 停止进程");
    }
}
