# Spring Cloud Alibaba 架构构建 — eleder care-platform

智慧养老微服务平台 · 技术分享

---

## 今天的内容

- **Part 1** 认识 Spring Cloud Alibaba — 它是什么、有哪些组件、解决什么问题
- **Part 2** 项目基础架构 — 工程结构、公共模块、架构全景
- **Part 3** 组件深入 — Nacos 注册发现、Gateway 网关、Sentinel 流量治理
- **Part 4** 演示 — 新建微服务、公共模块使用

流程：概念认知 → 地基搭建 → 组件深入 → 动手演示

---

## Spring Cloud Alibaba 是什么？

| 定位 | 与 Spring Cloud 的关系 |
|------|----------------------|
| Spring Cloud 体系的 **阿里巴巴实现**，为微服务提供一站式解决方案，核心组件经阿里双 11 流量考验 | Spring Cloud 定义 **接口规范**（如 DiscoveryClient），Spring Cloud Alibaba 提供 **具体实现**。类比：JDBC 接口 vs MySQL 驱动 |

**三层结构：** Spring Boot（应用基座）+ Spring Cloud（微服务抽象层）+ Spring Cloud Alibaba（阿里中间件实现）

---

## Spring Boot → Spring Cloud → Spring Cloud Alibaba

| 第一层：Spring Boot | 第二层：Spring Cloud | 第三层：Spring Cloud Alibaba |
|-------------------|-------------------|--------------------------|
| **应用基座**：自动配置 + 起步依赖 + 内嵌容器，不用管 XML、不用管 Tomcat。我们用：spring-boot-starter-web、spring-boot-starter-mail... | **微服务抽象层**：定义服务发现、负载均衡、网关等 **接口规范**，不提供具体实现。接口：DiscoveryClient、LoadBalancerClient... | **阿里中间件实现层**：用 Nacos 实现服务发现，用 Sentinel 实现流量治理。实现：NacosDiscovery、SentinelResource... |

**一句话：** Spring Boot 搭房子 → Spring Cloud 画图纸 → Spring Cloud Alibaba 给你钢筋混凝土

---

## 一个微服务系统需要解决哪些问题？

| 问题 | 描述 | 方案 |
|------|------|------|
| 服务怎么互相找到？ | 几十个服务，IP 和端口随时变化，不可能硬编码 | **需要注册中心** |
| 请求从哪进来？ | 前端不能直接调 8 个服务，鉴权、路由、跨域不能分散 | **需要 API 网关** |
| 流量爆发怎么办？ | 一个服务被打爆 → 连锁拖垮整个系统，没有保护机制就会雪崩 | **需要限流熔断** |
| 公共代码怎么复用？ | 8 个服务各自写鉴权、异常、响应格式，改一处漏七处 | **需要公共模块** |

---

## Spring Cloud Alibaba 核心组件

| 组件 | 说明 |
|------|------|
| **Nacos** — 注册中心 + 配置中心 | 替代 Eureka + Config。服务启动自动注册，下线自动剔除，配置统一管理，变更实时生效 |
| **Sentinel** — 流量治理 | 替代 Hystrix。QPS 限流、熔断降级、系统自适应保护，Dashboard 实时监控 |
| **Spring Cloud Gateway**（Spring 官方） | API 网关：统一入口、路由转发、过滤器链。基于 WebFlux 响应式，高吞吐 |
| **OpenFeign**（Spring 官方） | 声明式 HTTP 客户端。写接口像写本地方法，集成负载均衡 |

链路：Nacos 服务发现 → Gateway 统一入口 → Sentinel 流量保护 → Feign 服务调用

---

## 工程结构 — Maven 多模块 + BOM 版本管理

```xml
<!-- el dercare-platform/pom.xml — 父 POM（packaging=pom）-->
<properties>
    <maven.compiler.source>17</maven.compiler.source>
    <spring-boot.version>3.2.4</spring-boot.version>
    <spring-cloud.version>2023.0.1</spring-cloud.version>
    <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
</properties>

<!-- 三个 BOM（scope=import, type=pom）— 子模块不写 version -->
spring-boot-dependencies 3.2.4
spring-cloud-dependencies 2023.0.1
spring-cloud-alibaba-dependencies 2023.0.1.0

<!-- 10 个子模块 -->
common（core, security, file, notification, audit）
gateway
service-auth
service-care
service-vital
service-family
service-iot
service-alert
service-operation
service-ai
```

| BOM 是什么 | 怎么用 | 为什么这样设计 |
|-----------|--------|--------------|
| 带 dependencyManagement 的 **空 POM**，不包含 jar，只声明版本号 | 父 POM 导入 3 个 BOM，子模块引用不写 version，Maven 自动向上查找 | 版本统一，杜绝冲突；升级只改父 POM 一处，所有模块瞬间一致 |

---

## 公共模块 — 可复用的基础设施

没有它：8 个服务各自定义响应格式、各自写鉴权、各自处理异常，代码重复且不一致。

---

## 公共模块 — 两个核心 jar

### common-core — 基础约定

```java
// R<T> 统一响应 — 四个字段永不改
public class R<T> {
    int code; String msg; T data; String traceId;
    ok(T) / fail(IErrorCode) // 两个方法覆盖所有场景
}

// 错误码 — 10xxxx=系统 11xxxx=安全
enum SystemErrorCode implements IErrorCode {
    INTERNAL_ERROR(100001), UNAUTHORIZED(110001)...
}

// BizException — 故意不记堆栈（性能优化）
throw new BizException(UNAUTHORIZED)
  → GlobalExceptionHandler 拦截 → R.fail(110001)
```

### common-security — 安全底座

```yaml
# application.yml 统一配置
eldercare.security:
    secret: ElderCarePlatform2024...
    access-token-expiration: 7200
    whitelist: [/auth/login, /auth/register]
```

**四大能力：**
- `JwtTokenProvider` — 签发/校验/刷新（纯 POJO）
- `JwtAuthenticationFilter` — OncePerRequestFilter
- `@RequireRole` — AOP 方法级鉴权
- `FeignAuthInterceptor` — token + traceId 自动传播

**关键设计：** JwtTokenProvider 是纯 POJO，无 Servlet 依赖，Gateway（WebFlux）和业务服务（Servlet）都能用。

---

## eldercare-platform 架构全景

```
前端 / 客户端
    │  HTTP / WebSocket
    │
┌──────────────────────────────────┐
│  Gateway :9999（WebFlux）         │
│  JWT 鉴权 | 路由转发 | Sentinel 限流 │
└──────────────────────────────────┘
    │  lb:// 负载均衡
    │
┌──────┐  ┌──────┐  ┌───────┐  ┌────────┐  ┌────────┐
│ auth │  │ care │  │ vital │  │ family │  │  ...   │
│ :8090│  │ :8083│  │ :8091 │  │ :8092  │  │        │
└──────┘  └──────┘  └───────┘  └────────┘  └────────┘
    │         │         │           │            │
    └─────────┴─────────┴───────────┴────────────┘
                    │
              Nacos :8848
                    │
        ┌───────────┴───────────┐
        │                       │
  MySQL / Redis / MinIO / EMQX
```

---

## Nacos — 服务注册与发现

没有它：服务间硬编码 IP，挂了不知道，扩容全靠改配置重启。

---

## Nacos 怎么工作？

| 注册 | 心跳 | 发现 | 负载均衡 |
|------|------|------|---------|
| 服务启动 → 向 Nacos 注册，携带 IP、端口、健康状态，Dashboard 实时可见 | 注册后每 **5s** 发心跳，15s 无心跳 → 不健康，30s 无心跳 → 自动剔除 | 调用方启动时拉取服务列表，订阅变更通知 → 本地缓存。Nacos 宕机，缓存仍可用 | Gateway 用 **lb://service-name**，LoadBalancer 查 Nacos 拿实例，轮询选择 → 转发请求 |

---

## Nacos — 我们的接入方式

### 业务服务配置（service-auth/application.yml）

```yaml
server:
    port: 8090
spring:
    application:
        name: service-auth
    cloud:
        nacos:
            discovery:
                server-addr: 127.0.0.1:8848
```

三步：加依赖 → 配 yml → @EnableDiscoveryClient

### Gateway 路由配置

```yaml
spring.cloud.gateway.routes:
    - id: route-auth
      uri: lb://service-auth
      predicates:
          - Path=/auth/**
```

lb:// = LoadBalancer 从 Nacos 查 IP 列表 + 轮询

链路：服务启动注册到 Nacos → Gateway lb://service-name 查 Nacos 转发 → 下游服务只需读请求头，不感知来源

---

## Gateway — 统一 API 网关

没有它：8 个地址暴露给前端，CORS 配 8 遍，鉴权写 8 遍，内部架构全暴露。

---

## Gateway 怎么工作？

| 路由匹配 | 过滤器链 | WebFlux 响应式 | 鉴权集中化 |
|---------|---------|--------------|----------|
| 前端只访问 :9999 一个地址。/auth/** → service-auth，/elderly/** → service-care。一个入口，背后 8 个服务 | 多个 GlobalFilter 串成链，order 越小越先执行。-100: JWT 鉴权，-95: RBAC，-90: WebSocket 限流 | 基于 Netty NIO → 非阻塞 I/O，少量线程处理大量连接，Gateway 天然高吞吐 | 认证只在 Gateway 做一次，通过后设 X-User-Id 请求头，下游服务无条件信任 |

---

## Gateway — JwtAuthGlobalFilter 实现

```java
// order=-100，在过滤器链中最先执行
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 白名单路径直接放行（/auth/login, /auth/register...）
        if (isWhitelisted(path)) return chain.filter(exchange);

        // 2. 提取 token — Header "Authorization: Bearer xxx"，也兼容 ?token=
        String token = extractToken(exchange.getRequest());
        LoginUser loginUser = jwtTokenProvider.getLoginUser(token);

        // 3. ★ 防伪造：先删除外部传入的 X-User-* 头，再设置可信值
        ServerHttpRequest req = exchange.getRequest().mutate()
                .headers(h -> { h.remove("X-User-Id"); h.remove("X-User-Roles"); })
                .header("X-User-Id", String.valueOf(loginUser.getUserId()))
                .header("X-User-Roles", String.join(",", roles))
                .build();

        return chain.filter(exchange.mutate().request(req).build());
    }
}
```

**配套过滤器：**
- `RbacAuthGlobalFilter` (order=-95)：路径→角色映射
- `WebSocketConnectionLimitFilter` (order=-90)：每用户 3 连接

**WebSocket 兼容：** 浏览器 WebSocket API 不能设自定义 Header，所以 Gateway 额外支持从 `?token=xxx` 读令牌。

---

## Sentinel — 流量治理

没有它：突发流量打爆服务 → 连锁雪崩 → 凌晨三点重启 → 重启了又挂。

---

## Sentinel 怎么保护系统？

| 限流 | 熔断 | 隔离 | 控制台 |
|------|------|------|--------|
| 设定 QPS 上限 → 超了就拒绝。滑动窗口算法，比固定窗口更平滑，不会"边界突发" | 下游错误率高 → 直接拒绝（快速失败），等冷却时间过 → 放一个请求探测，正常就恢复，不行继续熔断 | 信号量限制并发线程数，一个慢接口不会占满所有线程，保护其他正常接口不受影响 | Dashboard 实时监控 QPS/RT/异常，规则可动态修改实时生效，生产级规则可推至 Nacos 持久化 |

流程：请求进来 → 滑动窗口计数 QPS → 未超阈值放行 | 超阈值拒绝 429

---

## Sentinel — 我们的接入方式

### Gateway 限流配置

```yaml
spring.cloud.sentinel:
    transport.dashboard: 127.0.0.1:8080
    eager: true       # 启动即加载，不等首次请求
```

### 限流规则（SentinelGatewayConfig）

```java
// @PostConstruct 中硬编码 5 个路由组，每组 QPS=100
for (String api : Arrays.asList(
        "route-auth", "route-admin",
        "route-server", "route-elderly",
        "route-family")) {
    rules.add(new GatewayFlowRule(api)
            .setCount(100)
            .setIntervalSec(1));
}
GatewayRuleManager.loadRules(rules);
```

### 被限流后返回什么

```java
@Bean @Order(-2)
public WebExceptionHandler sentinelBlockHandler() {
    return (exchange, ex) ->
        ServerResponse.status(429)
            .bodyValue(R.fail(TOO_MANY_REQUESTS));
}
```

5 个路由组 x QPS=100，超限 → 429 + R.fail(100005)，eager=true 冷启动也安全。

---

## 演示：5 分钟新建一个微服务

### Step 1：pom.xml

```xml
<!-- service-xxx/pom.xml -->
<parent>
    com.eldercare:eldercare-platform
</parent>

<dependencies>
    common-core
    common-security
    spring-boot-starter-web
    spring-cloud-starter-alibaba-nacos-discovery
</dependencies>
```

### Step 2：application.yml

```yaml
server:
    port: 8099          # 新端口
spring:
    application.name: service-xxx
    cloud.nacos.discovery.server-addr: 127.0.0.1:8848
eldercare.security:
    enabled: true
    secret: ElderCarePlatform2024...
```

### Step 3：启动类

```java
@SpringBootApplication
@EnableDiscoveryClient
public class ServiceXxxApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceXxxApplication.class, args);
    }
}
```

### Step 4：Controller（使用统一响应 + 异常 + 鉴权）

```java
@RestController
public class XxxController {
    @GetMapping("/hello")
    public R<String> hello() {
        return R.ok("Hello from service-xxx!");
    }
}
```

---

## 演示：公共模块怎么用

### 返回统一响应

```java
// 成功
return R.ok(user);
return R.ok();

// 失败
return R.fail(SystemErrorCode.UNAUTHORIZED);
return R.fail(100001, "参数错误");

// 响应: {"code":0,"msg":"success","data":{...},"traceId":"abc"}
```

### 抛出业务异常

```java
throw new BizException(UNAUTHORIZED);
throw new BizException(FORBIDDEN);

// GlobalExceptionHandler 自动拦截
// → 转为 R.fail(110001) + 对应 HTTP 状态码
```

### 方法级权限控制

```java
@RequireRole({ADMIN, OPERATOR})
@PostMapping("/users")
public R<User> createUser(...) { ... }

// 获取当前用户
LoginUser user = SecurityContextHolder.getLoginUser();
Long userId = SecurityContextHolder.getCurrentUserId();
```

### Feign 服务间调用

```java
@FeignClient(name = "service-auth")
public interface AuthClient {
    @GetMapping("/auth/user/{id}")
    R<User> getUser(@PathVariable Long id);
}
```

---

## 回顾

**Spring Cloud Alibaba** = Spring Cloud 规范的阿里实现

- **Nacos** ：服务启动自动注册，心跳保活，lb:// 负载均衡
- **Gateway**：统一入口，过滤器链，鉴权集中，防伪造头
- **Sentinel**：QPS 限流，熔断降级，信号量隔离，eager 启动加载
- **common-core**：R\<T\> + BizException + 错误码 → 统一约定
- **common-security**：JWT + AOP + Feign 拦截器 → 自动装配
- **Maven BOM**：父 POM 锁定版本 → 10 个子模块零冲突

先认识它是什么 → 理解它解决什么问题 → 再看我们怎么用的
