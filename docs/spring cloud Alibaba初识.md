# Spring Cloud Alibaba 微服务架构构建 — 项目总结报告

> 项目名称：eldercare-platform 智慧养老微服务平台
> 技术栈：Spring Boot 3.2.4 + Spring Cloud 2023.0.1 + Spring Cloud Alibaba 2023.0.1.0
> JDK 版本：17

---

## 一、项目背景与目标

eldercare-platform 是一个面向智慧养老场景的微服务系统，旨在通过 Spring Cloud Alibaba 技术栈构建一套稳定、可扩展的微服务基础设施。项目采用 Maven 多模块架构，包含 10 个子模块，覆盖认证、养老关怀、健康监测、家属互联、IoT 设备接入、告警推送、运营管理和 AI 服务等业务领域。

---

## 二、技术架构概述

### 2.1 三层技术体系

| 层次 | 角色 | 说明 |
|------|------|------|
| Spring Boot | 应用基座 | 自动配置 + 起步依赖 + 内嵌容器，快速构建单个微服务 |
| Spring Cloud | 微服务抽象层 | 定义服务发现、负载均衡、网关等接口规范 |
| Spring Cloud Alibaba | 阿里中间件实现层 | 提供 Nacos、Sentinel 等具体实现 |

### 2.2 核心组件选型

| 组件 | 用途 | 选型理由 |
|------|------|---------|
| Nacos | 注册中心 + 配置中心 | 替代 Eureka + Config，一体化方案，支持服务发现与配置管理 |
| Sentinel | 流量治理 | 替代 Hystrix，支持 QPS 限流、熔断降级、系统自适应保护 |
| Spring Cloud Gateway | API 网关 | 基于 WebFlux 响应式模型，高吞吐、非阻塞 |
| OpenFeign | 声明式 HTTP 调用 | 集成负载均衡，声明式接口调用 |

### 2.3 架构全景

系统架构分层如下：

- **接入层**：客户端通过 HTTP/WebSocket 统一访问 Gateway（端口 9999）
- **网关层**：Gateway 负责 JWT 鉴权、路由转发、Sentinel 流量限流
- **业务服务层**：8 个业务微服务通过 Nacos 注册发现，经 lb:// 负载均衡调用
- **基础设施层**：MySQL、Redis、MinIO（对象存储）、EMQX（MQTT 消息中间件）

---

## 三、工程结构设计

### 3.1 Maven 多模块架构

父 POM 管理全局依赖版本，通过三个 BOM（Bill of Materials）实现版本统一：

- `spring-boot-dependencies` 3.2.4
- `spring-cloud-dependencies` 2023.0.1
- `spring-cloud-alibaba-dependencies` 2023.0.1.0

### 3.2 模块划分

| 模块类型 | 模块名称 | 职责 |
|---------|---------|------|
| 公共模块 | common-core | 统一响应 R\<T\>、BizException、错误码枚举 |
| 公共模块 | common-security | JWT 令牌、鉴权过滤器、AOP 方法级鉴权、Feign 拦截器 |
| 公共模块 | common-file | 文件上传下载 |
| 公共模块 | common-notification | 通知推送 |
| 公共模块 | common-audit | 操作审计 |
| 网关 | gateway | API 统一入口，WebFlux 响应式网关 |
| 业务服务 | service-auth | 认证授权 |
| 业务服务 | service-care | 养老关怀服务 |
| 业务服务 | service-vital | 健康监测 |
| 业务服务 | service-family | 家属互联 |
| 业务服务 | service-iot | IoT 设备接入 |
| 业务服务 | service-alert | 告警推送 |
| 业务服务 | service-operation | 运营管理 |
| 业务服务 | service-ai | AI 服务 |

### 3.3 BOM 版本管理原理

BOM 本质是一个带 `dependencyManagement` 的空 POM，不包含实际 jar，只声明版本号。父 POM 导入后，子模块引用依赖时无需指定 version，Maven 自动向上查找，实现全项目版本统一。升级时仅修改父 POM 一处即可。

---

## 四、公共模块设计

### 4.1 common-core — 基础约定

统一了全系统的数据交互格式和异常处理规范：

- **统一响应 R\<T\>**：code + msg + data + traceId 四字段，提供 ok()/fail() 静态工厂方法，覆盖所有业务场景
- **错误码体系**：10xxxx 系统级错误，11xxxx 安全级错误，通过 IErrorCode 接口规范枚举实现
- **BizException**：业务异常，故意不记录堆栈以优化性能，由 GlobalExceptionHandler 统一拦截转为 R.fail()

### 4.2 common-security — 安全底座

提供开箱即用的安全能力：

- **JwtTokenProvider**：纯 POJO 设计，无 Servlet API 依赖，Gateway（WebFlux）与业务服务（Servlet）通用
- **JwtAuthenticationFilter**：基于 OncePerRequestFilter 的 JWT 校验过滤器
- **@RequireRole 注解**：基于 AOP 的方法级角色鉴权
- **FeignAuthInterceptor**：服务间调用时自动传播 token 与 traceId

### 4.3 关键设计亮点

JwtTokenProvider 的纯 POJO 设计是核心亮点。Gateway 基于 WebFlux（反应式编程模型），业务服务基于 Servlet（传统同步模型），二者对请求处理方式不同。通过将 JWT 操作封装为纯 POJO，两种运行时环境可共享同一套令牌处理逻辑，无需重复实现。

---

## 五、组件深入

### 5.1 Nacos — 服务注册与发现

#### 工作机制

| 阶段 | 行为 | 参数 |
|------|------|------|
| 注册 | 服务启动时向 Nacos 注册 IP、端口、健康状态 | server-addr: 127.0.0.1:8848 |
| 心跳 | 每 5s 发送心跳维持活跃 | 15s 无心跳标记不健康，30s 剔除 |
| 发现 | 调用方启动时拉取并订阅服务列表，本地缓存 | Nacos 宕机时缓存仍可用 |
| 负载均衡 | Gateway 通过 lb:// 前缀自动负载均衡 | 轮询策略选择实例 |

#### 接入方式

三步完成服务注册：添加依赖 → 配置 application.yml → 启动类标注 @EnableDiscoveryClient。Gateway 通过 `lb://service-name` 格式进行服务发现与负载均衡转发。

### 5.2 Gateway — 统一 API 网关

#### 核心能力

- **路由匹配**：前端仅访问单一入口（:9999），根据路径前缀路由至对应后端服务
- **过滤器链**：多个 GlobalFilter 按 order 排序串行执行，JWT 鉴权（order=-100）→ RBAC 鉴权（order=-95）→ WebSocket 限流（order=-90）
- **WebFlux 响应式**：基于 Netty NIO 的事件驱动模型，少量线程处理大量并发连接

#### JwtAuthGlobalFilter 设计要点

1. 白名单路径（如 /auth/login）直接放行
2. 从 Authorization Header 或查询参数 token 中提取 JWT
3. **防伪造机制**：先删除外部传入的 X-User-Id、X-User-Roles 请求头，再设置网关解析后的可信值

### 5.3 Sentinel — 流量治理

#### 保护机制

- **限流**：滑动窗口算法计算 QPS，超过阈值返回 429，防止流量尖峰
- **熔断**：下游错误率过高时快速失败，冷却期后尝试探测恢复
- **隔离**：信号量控制并发线程数，避免单接口慢请求耗尽线程池

#### 项目中的配置

Gateway 层配置 `eager: true` 实现启动即加载规则。硬编码 5 个路由组（auth、admin、server、elderly、family），每组 QPS 上限 100。超出限制时由 SentinelBlockHandler 返回统一响应格式的 429 错误。

---

## 六、快速开发演示

### 6.1 新建微服务的四个步骤

1. **pom.xml**：继承父 POM，引入 common-core、common-security、web 和 nacos-discovery 依赖
2. **application.yml**：配置端口、服务名、Nacos 地址、安全密钥
3. **启动类**：@SpringBootApplication + @EnableDiscoveryClient
4. **Controller**：直接使用 R.ok() 返回统一响应

### 6.2 公共模块使用示例

- **统一响应**：R.ok(data)、R.fail(errorCode)
- **业务异常**：throw new BizException(UNAUTHORIZED)，全局异常处理器自动拦截
- **方法级鉴权**：@RequireRole({ADMIN}) 注解声明访问权限
- **服务间调用**：@FeignClient 声明式接口调用，自动负载均衡与 token 传播

---

## 七、项目亮点总结

1. **Maven BOM 统一版本管理**：三个 BOM 锁定所有依赖版本，子模块零配置、零冲突
2. **公共模块高复用性**：common-core 与 common-security 被打磨为内部公共 jar，新服务引入即获得统一响应、异常处理、JWT 鉴权能力
3. **纯 POJO 核心设计**：JwtTokenProvider 无 Servlet 依赖，Gateway（WebFlux）与业务服务（Servlet）通用
4. **请求头防伪造**：Gateway 在鉴权时主动删除外部请求头再设置可信值，避免下游信任链被绕过
5. **Sentinel eager 模式**：启动即加载限流规则，冷启动阶段同样安全
6. **完整服务治理体系**：注册发现（Nacos）→ 统一入口（Gateway）→ 流量保护（Sentinel）→ 服务调用（Feign），形成完整闭环
