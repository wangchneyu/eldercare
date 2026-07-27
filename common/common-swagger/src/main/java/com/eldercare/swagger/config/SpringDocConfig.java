package com.eldercare.swagger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * SpringDoc OpenAPI 自动配置
 * <p>
 * 各服务只需在 application.yml 中配置:
 * <pre>{@code
 * server.servlet.context-path: /service-xxx
 * }</pre>
 * 即可在网关统一入口看到各服务的 API 文档。
 * <p>
 * Swagger UI 访问路径: {context-path}/swagger-ui.html
 */
@AutoConfiguration
public class SpringDocConfig {

    @Value("${spring.application.name:eldercare-service}")
    private String applicationName;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName + " API 文档")
                        .description("中铁和园智慧养老平台 - " + applicationName + " 服务接口文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("中铁和园技术团队"))
                        .license(new License()
                                .name("内部使用")
                                .url("https://eldercare.cn")))
                .servers(buildServers())
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("在 Authorization 头中传入 Bearer Token")))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"));
    }

    /**
     * 构建 API Server 列表（网关 + 本地直连）
     */
    private List<Server> buildServers() {
        Server gwServer = new Server()
                .url("/api")
                .description("通过网关访问（生产）");

        Server localServer = new Server();
        if (StringUtils.hasText(contextPath)) {
            localServer.setUrl(contextPath);
        } else {
            localServer.setUrl("/");
        }
        localServer.setDescription("本地直连（开发调试）");

        return List.of(gwServer, localServer);
    }
}
