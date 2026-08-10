---
name: eldercare-code-standards
description: 审查、设计和修改中铁和园智慧养老平台代码，使 Java/Spring 分层、REST 与 Feign 契约、RocketMQ 事件、错误码与异常、日志追踪、数据库和安全实现符合项目规范。用于代码评审、规范冲突检查、功能开发、重构、接口或消息契约变更、错误码登记以及质量门禁验证。
---

# Eldercare Code Standards

## Project Architecture

### 技术栈

| 组件 | 版本 |
| --- | --- |
| Java | 17 |
| Spring Boot | 3.2.4 |
| Spring Cloud | 2023.0.1 |
| Spring Cloud Alibaba | 2023.0.1.0 |
| MyBatis-Plus | 3.5.7（boot3 变体） |
| Lombok | 1.18.30 |
| RocketMQ Starter | 2.2.3 |
| JJWT | 0.12.6 |
| PostgreSQL JDBC | 42.6.2 |
| Redisson | 3.27.0 |
| SpringDoc OpenAPI | 2.5.0 |
| Hutool | 5.8.34 |

### 微服务模块

| 模块 | 端口 | 职责 |
| --- | --- | --- |
| gateway | 9999 | 统一网关：路由、JWT 验签、RBAC、限流、链路追踪 |
| service-operation | 8087 | 运营管理 + 认证（登录/注册/Token 刷新/登出） |
| service-care | 8083 | 照护服务 |
| service-family | 8092 | 家属服务 |
| service-vital | 8091 | 生命体征 |
| service-iot | 8094 | IoT 设备管理 |
| service-ai | 8093 | AI 推理 |
| service-alert | 8095 | 告警服务 |

### 公共组件（common）

| 模块 | 职责 |
| --- | --- |
| common-core | 统一响应体 `R<T>`、异常体系、错误码、工具类、MQ 基础设施 |
| common-security | JWT 鉴权、双模式过滤器、`@RequireRole` AOP、Feign 身份透传 |
| common-redis | RedisTemplate JSON 序列化、分布式锁、Snowflake workerId 分配 |
| common-feign | 7 个服务 Client + FallbackFactory、超时策略 |
| common-file | 文件存储抽象（本地/COS） |
| common-notify | 短信/语音/微信通知抽象 |
| common-audit | `@AuditLog` 注解 + AOP 操作日志 |
| common-swagger | SpringDoc OpenAPI 零代码接入 |

### 中间件依赖

| 中间件 | 用途 | 端口 |
| --- | --- | --- |
| Nacos | 服务注册发现 + 配置中心 | 8848 / 9848 / 9849 |
| Redis | Token 黑名单、分布式锁、缓存 | 6379 |
| Sentinel Dashboard | 流控规则管理（可选） | 8858 |
| RocketMQ | 异步消息（按需部署） | 9876 |

### Gateway Filter 链（执行顺序）

```
AccessLog → TraceId → JwtAuth → TokenBlacklist → RbacAuth → WSLimit → Sentinel
```

- 网关只做验证（Token 合法性、黑名单、角色路径匹配），不涉及数据库或业务逻辑
- 认证透传：`X-User-Id`、`X-Username`、`X-User-Roles`
- 链路追踪：`X-Trace-Id`

### 配置管理

- 本地 `application.yml` 仅保留引导配置（端口、服务名、Nacos 连接地址）
- 核心业务配置（路由、RBAC、Sentinel、安全策略）由 Nacos Config 集中管理
- Data ID 命名：`service-{name}.yaml`（如 `service-gateway.yaml`）
- 使用 `spring.config.import: optional:nacos:xxx.yaml`，Nacos 不可用时回退本地

### 关键架构决策

- 认证业务归属 service-operation，不独立设 auth 服务
- 网关路由 `/auth/**` → `lb://service-operation`
- 中间件禁止 Docker 部署，使用 JAR 直启
- 统一响应体 `R<T>`：`{code, msg, data, traceId}`，成功码为 `0`

## Workflow

1. 确认任务是审查、设计还是修改；未获授权时只做只读检查。
2. 以 UTF-8 完整读取 [代码规范](references/code-standards.md)。涉及 REST 响应、错误码、异常、Feign 降级或 HTTP 状态时，同时以 UTF-8 完整读取 [错误码与异常规范](references/error-codes-and-exceptions.md)；Windows Shell 默认编码不是 UTF-8 时必须显式指定编码。
3. 检查仓库级构建配置（根 `pom.xml`）和相关模块，保留用户已有的未提交改动。
4. 将发现区分为：规范之间冲突、实现偏离规范、风险但尚未被规范覆盖。不要混为一谈。
5. 修改时同步更新源码、测试、契约和相关规范文档；不得只修一侧造成新的漂移。
6. 按风险验证：先做静态扫描，再运行受影响模块测试，最后执行全仓编译或打包。
7. 交付时先说明结果，再列关键改动、验证证据和仍存在的非本次范围问题。

## Apply the Standards

- 保持 Controller、Service、Mapper/Repository 和 assembler/converter 边界清晰。
- 将跨服务写操作建模为 MQ 事件；Feign 只声明用户请求链路中的只读、低延迟查询。
- 使用版本化 MQ 信封，并验证 `eventId`、`eventType`、`schemaVersion`、`occurredAt`、`traceId`、`producer` 和 `payload`。
- 仅用 `BizException` 表达已登记的领域规则失败；将远程依赖失败映射为 `RemoteCallException`。
- 只通过 `IErrorCode` 生成失败响应；禁止任意错误码、动态客户端错误文案或 HTTP 200 包装失败。
- 保证 `traceId` 在 JSON 响应体、协议例外响应头、Feign、MQ 和异步执行之间正确透传并清理 MDC。
- 使用雪花业务主键并在 REST/MQ JSON 中将 64 位 ID 序列化为字符串。
- 为契约、状态机、幂等、重试、事务消息和异常映射补充自动化测试。
- 新增业务服务必须引入 common-core + common-security，遵循统一响应体和鉴权规范。
- 网关层修改须保持「只验证不业务」原则，不得引入数据库操作或用户状态管理。
- 配置变更优先通过 Nacos Config 管理，避免硬编码到 application.yml。

## Build & Verify

```bash
# 安装公共模块到本地仓库
mvnw install -pl common -am "-Dmaven.test.skip=true"

# 编译全部服务
mvnw compile "-Dmaven.test.skip=true"

# 打包单个服务（如 gateway）
mvnw package -pl gateway -am "-Dmaven.test.skip=true"
```

- PowerShell 中 `-D` 参数必须加引号：`"-Dmaven.test.skip=true"`
- 启动顺序：Nacos → Redis → Gateway → 业务服务
- 健康检查：`GET /actuator/health`

## Review Output

按严重程度排列可操作发现，并链接到具体文件和行号。每项说明冲突规则、当前证据、可能影响和建议修正。若没有发现，明确说明已检查范围和剩余测试风险。

## Keep References Synchronized

本 Skill 的 `references/` 是可移植基线。仓库内存在 `docs/代码规范文档.md` 或 `docs/错误码及异常设计.md` 时，将仓库文档视为项目登记源。

- 修改规范时，同时更新对应仓库文档和本 Skill 的参考副本。
- 两者不一致时，不要静默选择；先指出差异，并按用户确认或最新已审批的项目文档执行。
- 不要在 `SKILL.md` 重复完整规则；详细内容只维护在参考文件中。
