# Gateway Service + 基础架构 产品需求文档

> 负责人：王晨宇 | 角色：Gateway Service 负责人 + 基础架构负责人
> 职责：确保各端请求通过网关鉴权路由到正确服务，维护所有服务共同依赖的 Maven 模块和公共组件。
> 规范依据：《错误码及异常设计》、《代码规范文档》、《微服务架构方案》

---

## 零、基础约定对齐

> 本节记录 PRD 与《微服务架构方案》之间的差异裁定。代码实现以本节为准，架构方案文档后续同步更新。

| 约定项 | 架构方案原文 | 本 PRD / 代码实际 | 裁定 |
|--------|-------------|------------------|------|
| Gateway 端口 | 8080 | **9999** | 以 9999 为准，避免与业务服务端口段冲突 |
| 路由前缀 | `/api/auth/**`、`/api/elder/**` | `/auth/**`、`/elderly/**`（无 `/api` 前缀） | 以无前缀为准，网关本身即 API 唯一入口，`/api` 冗余 |
| 老人端路由命名 | `/api/elder/**` | `/elderly/**` | 以 `/elderly/**` 为准，与 service-care 的 Controller 路径一致 |
| 服务注册名 | `elder-{service-name}`（如 `elder-care`） | `service-{name}`（如 `service-care`） | 以 `service-xxx` 为准，所有 `spring.application.name`、Feign `@FeignClient(name=...)`、Gateway `lb://` 已统一使用 |
| 限流阈值 | 登录 QPS≤20 / SOS QPS≤500 / 通用 QPS≤200 | 原 PRD 统一 QPS=100 | **采纳架构方案三级分档**，见 US1 FS-1.3 更新 |

---

## 一、Gateway Service（统一入口）

### 背景

当前 Gateway 已具备核心能力：JWT 鉴权（[JwtAuthGlobalFilter](file:///e:/workspace/project/eldercare-platform/gateway/src/main/java/com/eldercare/gateway/filter/JwtAuthGlobalFilter.java)）、RBAC 角色路径拦截（[RbacAuthGlobalFilter](file:///e:/workspace/project/eldercare-platform/gateway/src/main/java/com/eldercare/gateway/filter/RbacAuthGlobalFilter.java)）、WebSocket 连接限制（[WebSocketConnectionLimitFilter](file:///e:/workspace/project/eldercare-platform/gateway/src/main/java/com/eldercare/gateway/filter/WebSocketConnectionLimitFilter.java)）、Sentinel 限流（[SentinelGatewayConfig](file:///e:/workspace/project/eldercare-platform/gateway/src/main/java/com/eldercare/gateway/config/SentinelGatewayConfig.java)）。

但存在以下缺口：**部分业务服务的 HTTP 入口尚未开放**、**网关层缺少统一的全局错误响应**、**全链路 traceId 在 WebFlux 环境下未完整透传**。

---

### 用户故事 1：四端用户都能通过网关访问各自的服务

**作为** 服务端/管理端/老人端/亲属端的使用者
**我想要** 通过统一的网关入口（:9999）访问属于我端侧的所有功能
**以便于** 只需记住一个地址，且由网关统一做认证和权限校验，不需要各服务各自实现

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-1.1** 完整路由覆盖 | Gateway 必须为全部 8 个业务服务提供 HTTP 入口路由（基于 Nacos 服务发现，使用 `lb://service-{name}` 自动负载均衡），当前缺 3 条 |
| **FS-1.2** 角色准入控制 | 每条路由的 RBAC 角色映射必须与《代码规范文档》中 `RoleType` 枚举和业务实际角色对齐。映射配置从 Nacos Config（`eldercare.gateway.rbac.role-mappings`）加载，通过 Nacos Listener 监听变更**实时刷新**，无需重启网关 |
| **FS-1.3** 限流保护（三级分档） | 每条路由必须在 Sentinel 中注册对应的限流规则，阈值按业务优先级分级（见下表），防止绕过限流的盲区 |
| **FS-1.4** 鉴权白名单 | 无需鉴权的路径（如 `/auth/login`、`/auth/register`、`/actuator/health`）通过 Nacos 配置 `eldercare.gateway.auth.whitelist` 维护，支持动态增删，变更后实时生效。白名单路径跳过 JWT 验签和 RBAC 校验，直接放行 |

**Sentinel 限流分级（依据《微服务架构方案》3.1 节）：**

| 路由 | QPS 阈值 | 分级理由 |
|------|----------|----------|
| `/auth/login`、`/auth/register` | **20** | 防暴力破解，正常用户不可能 1 秒认证 20 次 |
| `/alert/**`（SOS/预警相关） | **500** | P0 生命安全主链路，不能因限流拒绝 SOS 请求 |
| 其余 6 条路由（`/admin/**`、`/server/**`、`/elderly/**`、`/family/**`、`/vital/**`、`/ai/**`） | **200** | 通用业务，200 QPS 对单台 4C4G 服务器合理 |

> 注：WebSocket 路由（`ws/alert/**`、`ws/family-living/**`）不计入 HTTP 限流计数器，由 `WebSocketConnectionLimitFilter` 独立管控（单用户 ≤ 3 连接）。

**当前已覆盖 5 条 → 目标 8 条：**

| 路由前缀 | 目标服务 | 适用端 | 状态 |
|----------|----------|--------|------|
| `/auth/**` | service-auth | 所有端（登录注册） | 已有 |
| `/admin/**` | service-operation | 管理端 | 已有 |
| `/server/**` | service-iot | 服务端 | 已有 |
| `/elderly/**` | service-care | 老人端 | 已有 |
| `/family/**` | service-family | 亲属端 | 已有 |
| `/vital/**` | service-vital | 老人端/服务端 | **待新增** |
| `/ai/**` | service-ai | 老人端/服务端 | **待新增** |
| `/alert/**` | service-alert | 管理端/服务端 | **待新增** |

**RBAC 映射补充：**

| 路由 | 允许角色 |
|------|----------|
| `/vital/**` | ADMIN, CAREGIVER, DOCTOR |
| `/ai/**` | ADMIN, CAREGIVER |
| `/alert/**` | ADMIN, CAREGIVER, OPERATOR |

**WebSocket 路由的角色校验：**

WebSocket 路由同样需要 RBAC 校验，校验在**握手阶段**完成（HTTP Upgrade 请求经过 `JwtAuthGlobalFilter` + `RbacAuthGlobalFilter`），握手成功后不再逐帧校验。

| WebSocket 路由 | 目标服务 | 允许角色 | 校验时机 |
|----------------|----------|----------|----------|
| `ws/alert/**` | service-alert | ADMIN, CAREGIVER, OPERATOR, FAMILY | 握手阶段（Upgrade 请求） |
| `ws/family-living/**` | service-family | FAMILY, ADMIN | 握手阶段（Upgrade 请求） |

- WebSocket 握手的 JWT 从 URL query 参数 `?token=` 提取（已由 `JwtAuthGlobalFilter` 支持）
- 握手阶段角色不匹配 → 拒绝 Upgrade，返回 403 + `{"code":110002}`
- 握手成功后连接数由 `WebSocketConnectionLimitFilter` 管控（单用户 ≤ 3），与 RBAC 解耦

#### 验收标准

- [ ] `GET /vital/health` → 200（携带有效 Token + 授权角色）
- [ ] `GET /ai/chat` → 200（携带有效 Token + 授权角色）
- [ ] `GET /alert/list` → 200（携带有效 Token + 授权角色）
- [ ] 未授权角色访问上述路径 → 403 + `{"code":110002}`
- [ ] Sentinel Dashboard 可见 8 条路由分组的 QPS 监控
- [ ] `/auth/login` QPS 超过 20 时返回 429 + `{"code":100005}`
- [ ] `/alert/**` QPS 超过 500 时返回 429 + `{"code":100005}`
- [ ] 其余路由 QPS 超过 200 时返回 429 + `{"code":100005}`

---

### 用户故事 2：任何错误场景下，前端都能收到统一格式的 JSON 错误响应

**作为** 前端开发者
**我想要** 无论网关出现什么问题（路由不存在、下游挂了、方法不对、Content-Type 错误），都能收到格式一致的 JSON 错误响应
**以便于** 前端可以统一处理错误，不需要为每种异常写不同的解析逻辑

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-2.1** 统一错误格式 | 所有网关层错误必须返回 `{"code": 六位业务码, "msg": "稳定文案", "data": null, "traceId": "..."}` |
| **FS-2.2** 场景全覆盖 | 覆盖路由未匹配、方法不支持、Content-Type 不支持、下游超时、下游不可用、未知异常 6 种场景 |
| **FS-2.3** 错误码合规 | 所有业务码必须来自 `SystemErrorCode` 枚举，不在枚举中定义之外创建新码 |

**错误映射表（严格依据《错误码及异常设计》1.1 节）：**

| 场景 | HTTP 状态 | 业务码 | 返回文案 |
|------|-----------|--------|----------|
| 请求了不存在的路径 | 404 | `100009` | 请求资源不存在 |
| 请求方法不对（如 POST 访问仅支持 GET 的路径） | 405 | `100003` | 请求方法不支持 |
| Content-Type 不被接受 | 415 | `100004` | 不支持的内容类型 |
| 触发 Sentinel 限流 | 429 | `100005` | 请求过于频繁，请稍后再试 |
| 下游服务连接超时或拒绝 | 502 | `100007` | 依赖服务暂不可用，请稍后再试 |
| 下游服务无健康实例 | 503 | `100006` | 服务暂不可用，请稍后再试 |
| 未预期的系统错误 | 500 | `100001` | 系统繁忙，请稍后重试 |

> 注：401（未认证）和 403（无权限）已由 [JwtAuthGlobalFilter](file:///e:/workspace/project/eldercare-platform/gateway/src/main/java/com/eldercare/gateway/filter/JwtAuthGlobalFilter.java) 和 [RbacAuthGlobalFilter](file:///e:/workspace/project/eldercare-platform/gateway/src/main/java/com/eldercare/gateway/filter/RbacAuthGlobalFilter.java) 覆盖，本次不重复实现。

#### 验收标准

- [ ] `curl http://localhost:9999/nonexistent` → 404 + `{"code":100009,"msg":"请求资源不存在"}`
- [ ] 对仅支持 GET 的路径发 POST → 405 + `{"code":100003}`
- [ ] 发 non-JSON Content-Type 到要求 JSON 的接口 → 415 + `{"code":100004}`
- [ ] 停掉下游服务后访问其路径 → 503 + `{"code":100006}`
- [ ] 触发下游超时 → 502 + `{"code":100007}`
- [ ] 以上所有响应的 `Content-Type` 均为 `application/json`
- [ ] 以上所有响应均包含非空的 `traceId` 字段

---

### 用户故事 3：出问题时能通过 traceId 快速定位到具体请求

**作为** 运维/开发人员
**我想要** 每个请求从进入网关到返回响应的全链路都带有同一个 traceId
**以便于** 出现问题时，用一个 traceId 就能关联网关日志、下游服务日志、Feign 调用日志和 MQ 消息日志

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-3.1** 生成规则 | 网关收到请求时，若请求头不带 `X-Trace-Id`，则生成一个全局唯一 ID（UUID 无横线格式）；若带则透传 |
| **FS-3.2** 防伪造 | 先移除外部传入的 `X-Trace-Id`，再设置为可信值，与现有 X-User-* 防伪造机制一致 |
| **FS-3.3** 响应携带 | 每次返回给客户端的 JSON 响应体中必须包含 `traceId` 字段 |
| **FS-3.4** 下游透传 | traceId 必须通过请求头透传给下游微服务 |
| **FS-3.5** WebFlux 兼容 | 在 Gateway 的 WebFlux 响应式环境下，traceId 不能因线程切换而丢失 |
| **FS-3.6** Filter 归属与顺序 | traceId 的生成/透传由**独立的 `TraceIdGlobalFilter`** 负责（不复用 JwtAuthGlobalFilter），order = `-110`（高于 JwtAuthGlobalFilter 的 `-100`），确保鉴权过滤器写 401 响应时 traceId 已就绪。执行顺序：`TraceIdGlobalFilter(-110)` → `JwtAuthGlobalFilter(-100)` → `RbacAuthGlobalFilter(-95)` → `WebSocketConnectionLimitFilter(-90)`。traceId 存入 Reactor Context（`Context.put("traceId", ...)`）以跨线程传递 |

#### 验收标准

- [ ] 不带 `X-Trace-Id` 头发起请求 → 响应体中 `traceId` 为 32 位 UUID 格式
- [ ] 带 `X-Trace-Id: my-custom-id` 头发起请求 → 响应体中 `traceId` 为 `my-custom-id`
- [ ] 外部传入的 `X-Trace-Id` 被网关清除后重新设置为可信值（防伪造）
- [ ] 401 拒绝响应中包含 traceId
- [ ] 403 拒绝响应中包含 traceId
- [ ] 下游服务收到的请求头中包含 `X-Trace-Id`
- [ ] 高并发场景下 traceId 不串号（100 并发请求，每个请求有不同 traceId）

### 用户故事 4：运维人员可在 Nacos 动态调整限流阈值，无需重启网关

**作为** 运维人员
**我想要** 在 Nacos 配置中心修改 Sentinel 限流规则后，Gateway 能实时生效，不需要重启服务
**以便于** 线上流量突发时能快速调整限流策略，不依赖开发人员改代码发版

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-4.1** 规则持久化 | Sentinel 规则从 Nacos 配置中心读取，不再仅依赖 `@PostConstruct` 硬编码 |
| **FS-4.2** 动态生效 | Nacos 配置变更后自动推送到 Gateway，Sentinel 规则实时更新，无需重启 |
| **FS-4.3** 硬编码兜底 | Nacos 不可用时，回退到 `SentinelGatewayConfig` 中硬编码的默认规则，确保限流不失效 |
| **FS-4.4** 格式兼容 | Nacos 中存储的规则格式与当前硬编码的 `GatewayFlowRule` 结构一致，便于迁移 |

**设计约束**：
- 当前 `@PostConstruct` 硬编码模式作为**冷启动兜底**保留，确保 Nacos 不可用时限流仍生效（eager=true 策略不变）
- Nacos 规则加载后**合并覆盖**同名 API 分组的规则，不清除未在 Nacos 中定义的分组规则
- 规则变更日志以 INFO 级别记录变更前后的 QPS 阈值

#### 验收标准

- [ ] Nacos Dashboard 中修改 `route-auth` 的 QPS 阈值从 100 改为 50，Gateway 在 30 秒内生效
- [ ] 停掉 Nacos 后重启 Gateway，硬编码默认规则自动生效，8 条路由均有 QPS=100 保护
- [ ] Nacos 中新增的 API 分组规则与硬编码规则合并，互不覆盖未定义部分
- [ ] 规则变更在 Gateway 日志中以 INFO 级别输出

---

## 二、common-core 模块（公共基础约定）

### 背景

`common-core` 是全部 10 个微服务共同依赖的基础 jar，定义了统一响应格式（`R<T>`）、异常体系（`BizException`/`IErrorCode`/`SystemErrorCode`）和全局异常处理器（`GlobalExceptionHandler`）。

当前存在的合规性问题：**缺少 `RemoteCallException` 异常类型**（《错误码及异常设计》三、明确要求）、**`GlobalExceptionHandler` 未覆盖全部规定异常类型**（缺 5 种）、**`SystemErrorCode` 枚举号段与设计文档不一致**（存在文档未定义的 `100008`/`100009`/`110003`）、**未配置雪花 ID 的 JSON 字符串序列化**。

---

### 用户故事 4：服务间调用失败时，调用方能得到规范的异常信息

**作为** 业务服务开发者
**我想要** 当我通过 Feign 调用其他服务失败（超时、连接拒绝、对方返回 5xx）时，能 catch 到一个统一的 `RemoteCallException`
**以便于** 我不用关心底层是 `FeignException`、`RetryableException` 还是 `SocketTimeoutException`，统一处理即可

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-4.1** 异常统一 | common-core 提供 `RemoteCallException`，承载目标服务名、请求路径、原始异常 cause |
| **FS-4.2** 保留诊断信息 | 构造器必须保留 cause 和堆栈，支持 WARN/ERROR 两级日志 |
| **FS-4.3** 错误码映射 | `RemoteCallException` 经 `GlobalExceptionHandler` 拦截后，统一返回 `HTTP 502 + code 100007` |

#### 验收标准

- [ ] `RemoteCallException` 继承 `RuntimeException`
- [ ] 构造器接受 `(String serviceName, String requestUri, Throwable cause)`，保留 cause 和堆栈
- [ ] 在 Feign FallbackFactory 中能 `throw new RemoteCallException("service-auth", "/api/user", cause)`
- [ ] `GlobalExceptionHandler` 拦截后返回 `502 + {"code":100007}`

---

### 用户故事 5：所有可预见的异常场景都能被统一拦截并返回规范响应

**作为** 前端开发者 / 业务服务开发者
**我想要** 服务端抛出的各类异常（参数类型不对、方法不支持、Content-Type 错误、越权访问、远程调用失败等）都能被自动拦截并转为统一 JSON 响应
**以便于** 我不需要在每个 Controller 里写 try-catch，也不需要在每个 Feign 调用处手工处理异常

#### 功能规格

根据《错误码及异常设计》三、异常分类表，`GlobalExceptionHandler` 需要覆盖全部 6 类异常：

| 异常 | HTTP | 业务码 | 日志级别 | 状态 |
|------|------|--------|----------|------|
| `BizException` | 由枚举决定 | 枚举绑定 | WARN | 已有 |
| `MethodArgumentNotValidException`（@Valid 校验） | 400 | `100002` | WARN | 已有 |
| `HttpMessageNotReadableException`（请求体不可读） | 400 | `100002` | WARN | 已有 |
| `MissingServletRequestParameterException`（缺少参数） | 400 | `100002` | WARN | 已有 |
| `MethodArgumentTypeMismatchException`（类型转换） | 400 | `100002` | INFO | **待新增** |
| `HttpRequestMethodNotSupportedException`（方法不支持） | 405 | `100003` | INFO | **待新增** |
| `HttpMediaTypeNotSupportedException`（Content-Type 不支持） | 415 | `100004` | INFO | **待新增** |
| `AccessDeniedException`（越权） | 403 | `110002` | WARN | **待新增** |
| `RemoteCallException`（远程调用失败） | 502 | `100007` | WARN | **待新增** |
| `Exception`（兜底） | 500 | `100001` | ERROR | 已有 |

**参数校验失败的 `data` 字段格式（依据《错误码及异常设计》3.1 节）：**

当 `MethodArgumentNotValidException` 或 `MissingServletRequestParameterException` 触发时，`R<T>` 的 `data` 字段返回字段错误数组：

```json
{
  "code": 100002,
  "msg": "请求参数不合法",
  "data": [
    {"field": "phone", "reason": "格式不正确", "rejectedValue": "138****1234"},
    {"field": "age", "reason": "必须为正整数", "rejectedValue": "abc"}
  ],
  "traceId": "..."
}
```

- `rejectedValue` 必须经 `DesensitizeUtil` 脱敏，不得回显密码、证件号、完整手机号等敏感值
- 非校验类异常（类型转换、方法不支持等）的 `data` 保持 `null`

#### 验收标准

- [ ] 发送 `GET /api?page=abc`（page 期望 Integer）→ 400 + `{"code":100002}`
- [ ] 对仅支持 GET 的接口发送 POST → 405 + `{"code":100003}`
- [ ] 发送 `Content-Type: text/plain` 到要求 JSON 的接口 → 415 + `{"code":100004}`
- [ ] 无权限用户访问受保护接口 → 403 + `{"code":110002}`
- [ ] Feign 调用抛出 RemoteCallException → 502 + `{"code":100007}`
- [ ] 所有错误响应包含 `traceId` 字段
- [ ] 未预期异常在日志中以 ERROR 级别记录完整堆栈

---

### 用户故事 6：错误码体系与设计文档完全一致，杜绝自行发明的号段

**作为** 架构评审人 / 基础架构负责人
**我想要** `SystemErrorCode` 枚举中的每个错误码都能在设计文档《错误码及异常设计》2.2 节中找到对应定义
**以便于** 代码审查时可以直接对照文档验证，新增错误码不会与已有号段冲突

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-6.1** 号段合规 | `SystemErrorCode` 中只保留文档 2.2 节公共错误码表中列出的码 |
| **FS-6.2** 移除自增码 | 删除文档中不存在的 `VALIDATION_ERROR(100008)`、`CONFLICT(110003)` |
| **FS-6.3** 复用而非重复 | 参数校验异常统一使用 `BAD_REQUEST(100002)`，不再单独定义 `VALIDATION_ERROR` |
| **FS-6.4** 冲突码归属 | `110003` 属于认证授权号段（110000-119999），不应被公共模块占用；409 冲突码由各服务自行定义 |

**修正对照：**

| 操作 | 枚举常量 | 当前 code | 原因 |
|------|----------|-----------|------|
| 删除 | `VALIDATION_ERROR` | `100008` | 文档无此定义，统一用 `BAD_REQUEST(100002)` |
| 删除 | `CONFLICT` | `110003` | 认证授权号段不可混用；409 由各服务自定 |
| 保留 | `NOT_FOUND` | `100009` | 作为路由不存在的标准返回码 |

**修正后文件中的 SystemErrorCode 枚举值清单：**

```
100001 INTERNAL_ERROR        → 500 系统繁忙
100002 BAD_REQUEST           → 400 请求参数不合法（含参数校验/类型转换/请求体不可读）
100003 METHOD_NOT_ALLOWED    → 405 请求方法不支持
100004 UNSUPPORTED_MEDIA_TYPE → 415 不支持的内容类型
100005 TOO_MANY_REQUESTS     → 429 请求过于频繁
100006 SERVICE_UNAVAILABLE   → 503 服务暂不可用
100007 REMOTE_CALL_FAILED    → 502 依赖服务暂不可用
110001 UNAUTHORIZED          → 401 登录状态已失效
110002 FORBIDDEN             → 403 无权执行此操作
```

> **关于 `100009` 的登记说明**：当前《错误码及异常设计》2.2 节公共错误码表仅列出 9 个码（100001-100007、110001-110002），未包含 `100009`。但 1.1 节对 HTTP 404 的描述为"对应服务资源不存在码"，且号段 100000-109999 归属"系统与网关"。本 PRD 裁定 `100009` 用于**网关层路由未匹配**（非具体业务资源不存在），需提请《错误码及异常设计》2.2 节补充登记：`100009 | 404 | 请求资源不存在 | 网关路由未匹配或静态资源不存在`。各业务服务内部的资源不存在（如"长者档案不存在"）使用各自号段的错误码（如 `214001`），不复用 `100009`。

#### 验收标准

- [ ] `SystemErrorCode.values()` 共 9 个枚举值，与上方清单完全一致
- [ ] 全仓搜索 `VALIDATION_ERROR` / `SystemErrorCode.VALIDATION_ERROR` 返回 0 结果
- [ ] 全仓搜索 `CONFLICT` / `SystemErrorCode.CONFLICT` 返回 0 结果
- [ ] 原引用 `VALIDATION_ERROR` 的位置均改为 `BAD_REQUEST`

---

### 用户故事 7：JavaScript 前端不会因 Long 精度丢失导致数据错误

**作为** Web/H5 前端开发者
**我想要** 后端返回的雪花 ID（Java Long 类型）在 JSON 中自动序列化为字符串
**以便于** JavaScript 的 `Number` 类型不会因超过安全整数范围（2^53）而丢失精度

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-7.1** 全局序列化 | MVC 服务的 Jackson ObjectMapper 全局配置：`Long` 和 `long` 序列化为字符串 |
| **FS-7.2** Gateway 同步 | WebFlux Gateway 使用独立的 ObjectMapper，需同步配置 |

#### 验收标准

- [ ] 业务服务返回 `{"id":1234567890123456789}` → 实际响应为 `{"id":"1234567890123456789"}`
- [ ] Gateway 返回的 `R<T>` 中包含 `Long` 字段时，同样序列化为字符串
- [ ] `Long` 反序列化不受影响（前端传字符串 `"123"` 可正常映射到 Java `Long` 参数）

---

### 补充：BaseEntity 公共基类与通用工具类

> 依据第一周任务卡（王晨宇周二：common-core 含 BaseEntity、通用工具类；孙杰周二：日期、ID生成、脱敏），common-core 还需提供以下公共基础设施。

**BaseEntity 公共基类（FS-7.3）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 雪花 ID，`@TableId(type = IdType.ASSIGN_ID)` |
| `createTime` | `LocalDateTime` | 创建时间，MyBatis-Plus `@TableField(fill = FieldFill.INSERT)` 自动填充 |
| `updateTime` | `LocalDateTime` | 更新时间，`@TableField(fill = FieldFill.INSERT_UPDATE)` 自动填充 |
| `deleted` | `Integer` | 逻辑删除标记（0=正常, 1=已删除），`@TableLogic` |

- 所有业务 Entity 继承 `BaseEntity`，不重复定义公共字段
- 雪花 ID 生成器使用 MyBatis-Plus 内置 `DefaultIdentifierGenerator`，WorkerId 从环境变量读取（单机开发默认 0）

**通用工具类清单（FS-7.4）：**

| 工具类 | 职责 | 来源 |
|--------|------|------|
| `DesensitizeUtil` | 手机号/证件号/姓名脱敏（US5 参数校验回显使用） | 王晨宇 |
| `DateTimeUtil` | 日期格式化、时区转换、相对时间计算 | 孙杰 |
| `IdGenerator` | 雪花 ID 生成（独立于 MyBatis-Plus，供非 DB 场景使用） | 孙杰 |
| `JsonUtil` | Jackson ObjectMapper 封装（复用全局配置，含 Long→String） | 王晨宇 |
| `TraceContext` | traceId 存取（MDC / Reactor Context 适配） | 王晨宇 |

- 工具类均为 `final` 类 + 私有构造器 + 静态方法，不可实例化
- 具体方法签名随各 US 实现逐步补充，本表仅确定类的归属和职责边界

---

## 三、公共组件层

### 背景

项目定义了 7 个 common 子模块，但其中 2 个是空壳（`common-redis` 无源码、`common-swagger` 无源码）、1 个 Feign 契约不完整（缺少 `service-operation` 对应的 Client/RemoteDTO/FallbackFactory）、Feign 默认超时未按规范设置。

---

### 用户故事 8：新服务引入 Redis 依赖即可直接使用，无需自行配置

**作为** 业务服务开发者
**我想要** 在我的服务 `pom.xml` 中引入 `common-redis` 后，就能直接 `@Autowired RedisTemplate<String, Object>` 使用 Redis
**以便于** 不需要在每个服务里重复配置连接池、序列化器、RedisTemplate Bean

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-8.1** 自动配置 | 引入 `common-redis` 后自动装配 `RedisTemplate<String, Object>` 和 `StringRedisTemplate` |
| **FS-8.2** 连接池 | 自动配置 Lettuce 连接池（max-active/ max-idle/ timeout 可配置） |
| **FS-8.3** 序列化 | value 使用 `Jackson2JsonRedisSerializer`，key 使用 `StringRedisSerializer` |
| **FS-8.4** 分布式锁 | 提供 `DistributedLock` 接口和基于 Redis 的实现，用于并发优化（非唯一正确性保障） |
| **FS-8.5** 缓存辅助 | 提供 `CacheHelper` 工具类封装常用 get/set/delete/expire 操作 |

#### 验收标准

- [ ] 业务服务 `pom.xml` 引入 `common-redis`，不额外配置任何 Bean，启动后能 `@Autowired RedisTemplate<String, Object>`
- [ ] `redisTemplate.opsForValue().set("test", user)` 存入的 `User` 对象在 Redis 中为 JSON 格式
- [ ] `DistributedLock.tryLock("order:123", 3, TimeUnit.SECONDS)` 可正常获取和释放锁
- [ ] `CacheHelper.set("key", value, Duration.ofMinutes(5))` 写入后 5 分钟自动过期

---

### 用户故事 9：新服务引入 Swagger 依赖即可自动生成接口文档

**作为** 前端开发者 / 接口联调人员
**我想要** 每个业务服务启动后自动生成 OpenAPI 文档
**以便于** 不需要手工维护接口文档，通过 Swagger UI 即可查看和测试所有接口

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-9.1** 自动装配 | 引入 `common-swagger` 后自动注册 `OpenAPI` Bean，无需额外配置 |
| **FS-9.2** 安全方案 | 全局配置 Bearer Token 认证方案，Swagger UI 可填入 Token 后测试受保护接口 |
| **FS-9.3** 开关控制 | 通过 `springdoc.api-docs.enabled=true/false` 控制文档开关（生产环境可关闭） |
| **FS-9.4** 分组 | 按服务名分组，便于区分不同服务的接口 |

#### 验收标准

- [ ] 引入 `common-swagger` 后访问 `http://localhost:{port}/swagger-ui.html` 能看到接口列表
- [ ] Swagger UI 上有 "Authorize" 按钮，可填入 `Bearer <token>`
- [ ] 设置 `springdoc.api-docs.enabled=false` 后 `/v3/api-docs` 返回 404

---

### 用户故事 10：服务间通过 Feign 调用时超时行为可控且一致

**作为** 业务服务开发者
**我想要** 所有 Feign 客户端的连接超时默认为 2 秒、读取超时默认为 5 秒
**以便于** 不会因一个慢服务导致调用方线程长时间阻塞，且超时参数可在 Nacos 配置中心统一调整

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-10.1** 默认超时 | 所有 Feign Client 默认 connect-timeout=2000ms, read-timeout=5000ms |
| **FS-10.2** Nacos 可覆盖 | 超时、重试、日志级别只在 Nacos 配置中心维护，不在本地 yml 硬编码覆盖 |
| **FS-10.3** 例外登记 | 超出默认值 3 倍的超时配置必须登记：调用方、超时预算、重试条件、降级行为 |

#### 验收标准

- [ ] 引入 `common-feign` 后，任意 Feign Client 连接超时 = 2s，读取超时 = 5s
- [ ] 通过在 Nacos 修改 `spring.cloud.openfeign.client.config.{service-name}.read-timeout` 可覆盖单个服务的超时

---

### 用户故事 11：运营管理服务也能通过 Feign 被其他服务调用

**作为** 业务服务开发者（如 service-care）
**我想要** 通过 `OperationClient` 调用运营管理服务的接口（如查询合同、排班信息）
**以便于** 在照护服务中可以直接获取老人的合同信息而不需要通过 HTTP 直连

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-11.1** 契约定义 | 新增 `OperationClient`、`OperationRemoteDTO`、`OperationFallbackFactory` |
| **FS-11.2** 与其他 6 个 Client 一致 | 参照 `AuthClient` + `AuthFallbackFactory` 的模式，使用 `@FeignClient` 声明 |

#### 验收标准

- [ ] `com.eldercare.common.feign.client` 包下存在 `OperationClient.java`
- [ ] `com.eldercare.common.feign.fallback` 包下存在 `OperationFallbackFactory.java`
- [ ] `OperationFallbackFactory` 在所有异常场景下抛出 `RemoteCallException`（不吞异常、不返回 null）
- [ ] 引入 `common-feign` 的消费方能 `@Autowired OperationClient`

**Feign 契约通用约束（适用于全部 7 个 Client，依据《代码规范文档》二.4 和《错误码及异常设计》五.1）：**

| 约束 | 说明 |
|------|------|
| **不返回 `R<T>`** | FeignClient 方法签名返回 `RemoteDTO`（或 `List<RemoteDTO>`），不包装统一响应体。服务端为 Feign 提供的内部接口也直接返回 DTO，不走 `R<T>` 包装 |
| **FallbackFactory 统一行为** | 全部 7 个 FallbackFactory 在所有异常场景下**必须抛出 `RemoteCallException`**，禁止返回 null、空集合或默认值（依据《代码规范》二.2：P0 处置、扣费、库存、核销和权限判定禁止以 fallback 默认值继续执行） |
| **仅限只读查询** | Feign 仅允许用户请求链路中的只读、低延迟查询；跨服务写操作、状态变更必须走 RocketMQ 异步（详见「十六、Feign 使用约束」） |
| **超时配置** | 默认 connect-timeout=2000ms, read-timeout=5000ms，仅在 Nacos 维护，不在本地 yml 硬编码 |

---

## 四、消息与通知基础设施

### 背景

《代码规范文档》二、和《错误码及异常设计》五、详细规定了 MQ 事件规范（信封字段：eventId / eventType / schemaVersion / occurredAt / traceId / producer / payload）、幂等消费、死信处理等要求。但当前项目中**MQ 公共能力完全空白**——无 RocketMQ 依赖、无事件基类、无消费抽象。

同时，`common-notification` 模块（定位为短信/微信订阅消息/语音呼叫的统一封装）当前仅有接口骨架，未完整实现各通道对接，各业务服务无法直接使用。

---

### 用户故事 12：服务间通过 MQ 事件实现异步解耦，事件格式统一规范

**作为** 业务服务开发者
**我想要** 在 common-core 中有一个标准的 `BaseEvent` 基类，继承它就能满足 MQ 事件信封字段要求
**以便于** 我不需要每次都手工拼 eventId、traceId、occurredAt 等字段，也不用担心漏掉规范要求的字段

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-12.1** 事件基类 | `BaseEvent` 抽象类包含 `eventId`、`eventType`、`schemaVersion`、`occurredAt`、`traceId`、`producer` 六个信封字段，子类只需定义 `payload` |
| **FS-12.2** occurredAt 规范 | 自动填充 UTC ISO-8601 格式的时间戳（`Instant.now().toString()`） |
| **FS-12.3** traceId 自动注入 | 构造时自动从 `TraceContext.currentTraceId()` 获取并填充 traceId |
| **FS-12.4** producer 自动注入 | 构造时自动从 `spring.application.name` 获取并填充 producer |
| **FS-12.5** Topic 常量类 | 在 `common-core` 中提供 `MQTopics` 常量类，集中定义全部跨服务 Topic 名称，各服务 Producer/Consumer 引用常量而非硬编码字符串。常量清单（依据《微服务架构方案》7.1 节）：`ELDER_VITAL_RAW`（体征流）、`ELDER_SOS_EVENT`（SOS/跌倒）、`ELDER_VITAL_ALERT`（超阈值）、`ELDER_ALERT_CONFIRMED`（预警确认→工单）、`ELDER_DAILY_EVENT`（日报事件）、`ELDER_SCHEDULE_CHANGE`（排班变更）、`ELDER_BILL_GENERATED`（账单生成） |

> 注：当前 `common-core` 中已有 `BaseEvent.java` 骨架，本次在此基础上升级为完整实现。

#### 验收标准

- [ ] `BaseEvent` 子类在构造时自动填充 `eventId`（UUID32）、`occurredAt`（UTC ISO-8601）、`traceId`、`producer`
- [ ] `BaseEvent` JSON 序列化后包含全部 6 个信封字段
- [ ] `eventType` 和 `schemaVersion` 由子类通过构造器传入，做必填校验
- [ ] `BaseEvent` 的 `eventId` 可作为消费幂等键
- [ ] `MQTopics` 常量类包含 7 个 Topic 常量，全仓搜索 Topic 字符串硬编码返回 0 结果（均引用常量）

---

### 用户故事 13：业务服务引入 common-notification 即可发送短信/微信/语音通知

**作为** 业务服务开发者
**我想要** 引入 `common-notification` 后，通过统一接口发送短信、微信订阅消息和语音呼叫
**以便于** 不需要在每个服务中各自对接阿里云短信 SDK、微信 API 和语音呼叫通道，且开发环境用 Mock 实现（写日志不真发）

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-13.1** 统一接口 | `NotifyService` 接口定义三个通道方法：`sendSms(String phone, String templateCode, Map<String,String> params)` / `sendWeChat(String openId, String templateId, Map<String,String> data)` / `sendVoice(String phone, String ttsContent)` |
| **FS-13.2** Mock 实现 | 开发环境（`eldercare.notification.mock=true`）使用 `MockNotifyServiceImpl`，仅以 INFO 日志输出通知内容，不真实调用第三方 API |
| **FS-13.3** 真实实现 | 生产环境使用 `AliyunSmsNotifyServiceImpl`（阿里云短信）+ `WeChatNotifyServiceImpl`（微信订阅消息）+ `VoiceNotifyServiceImpl`（语音呼叫），通过 `@ConditionalOnProperty` 切换 |
| **FS-13.4** 异步发送 | 通知发送使用 `@Async` 异步执行，不阻塞主业务流程（发送失败记录 ERROR 日志 + 重试 1 次，不回滚业务） |
| **FS-13.5** 自动配置 | `NotifyAutoConfiguration` 使用 `@AutoConfiguration` + SPI 注册，引入即生效 |

> 注：本模块定位与《微服务架构方案》四、公共组件库一致——"阿里云短信、微信订阅消息、语音呼叫封装"。作为 Jar 包嵌入各业务服务，不独立部署（架构方案已裁定：通知是无状态技术操作，独立部署反而引入单点）。

#### 验收标准

- [ ] 引入 `common-notification` 后，`NotifyService` Bean 可自动注入
- [ ] Mock 模式下调用 `sendSms` → INFO 日志输出手机号+模板+参数，不真实发送
- [ ] 真实模式下调用 `sendSms` → 阿里云短信 API 被调用（需配置 AccessKey）
- [ ] 发送失败时业务方法不抛异常，ERROR 日志记录失败原因
- [ ] `common-notification` 不依赖任何业务服务模块（纯技术封装）

---

## 五、工程治理

### 用户故事 14：Maven 模块注册完整，新增模块能正确参与构建

**作为** 基础架构负责人
**我想要** `common/pom.xml` 中声明的 `<modules>` 与实际的子模块目录一一对应
**以便于** `mvn compile` 时不会遗漏模块，CI 构建不会因模块未注册而跳过

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-14.1** 模块注册 | `common/pom.xml` 补充 `<module>common-redis</module>` 和 `<module>common-notification</module>` |
| **FS-14.2** 版本统一 | 父 POM 的 `dependencyManagement` 中声明 `redisson` 和 `springdoc` 版本 |

#### 验收标准

- [ ] 根目录执行 `mvn compile` 后，`common-redis/target` 和 `common-notification/target` 下有编译产物
- [ ] `common-redis` 和 `common-notification` 模块的依赖版本在父 POM 中统一管理，子模块 pom 中不写 version

---

## 六、实施优先级与依赖关系

> ⚠️ 本节为初始版本，已被「十八、更新后的实施优先级」替代。保留仅供历史参考。

```
第一优先级（可并行，无外部依赖）
  ├── [Gateway] FS-1.1~1.3 补充路由 + RBAC + Sentinel
  ├── [Gateway] FS-2.1~2.3 全局错误处理器
  ├── [Gateway] FS-3.1~3.5 traceId 透传
  ├── [Gateway] FS-4.1~4.4 Sentinel 规则 Nacos 动态化
  ├── [common-core] FS-6.1~6.4 SystemErrorCode 号段修正
  ├── [common-core] FS-7.1~7.2 Jackson Long序列化
  └── [common-core] FS-12.1~12.4 BaseEvent MQ 事件基类升级

第二优先级（依赖 common-core 修正完成）
  ├── [common-core] FS-4.1~4.3 新建 RemoteCallException（注：此 FS 编号与 Gateway FS-4 独立）
  ├── [common-core] FS-5 补充 GlobalExceptionHandler

第三优先级（依赖 common-core 修正完成，可并行）
  ├── [公共模块] FS-8.1~8.5 common-redis 实现
  ├── [公共模块] FS-9.1~9.4 common-swagger 实现
  ├── [公共模块] FS-10.1~10.3 Feign 默认超时配置
  ├── [公共模块] FS-11.1~11.2 补充 OperationClient
  └── [通知] FS-13.1~13.5 common-notification 短信/微信/语音通知

第四优先级（收尾串联）
  └── [工程治理] FS-14.1~14.2 父POM + 模块注册
```

> 注：common-core 的 FS-4（RemoteCallException）与 Gateway 的 FS-4（Sentinel 动态规则）编号相同但分属不同章节，实施时以所属模块区分。

---

## 七、总体验收清单

> ⚠️ 本节为初始版本，已被「十九、总体验收清单」替代。保留仅供历史参考。

### Gateway 统一入口
- [ ] 8 条 HTTP 路由全部可访问，对应限流生效
- [ ] 6 种错误场景（404/405/415/502/503/500）均返回统一 JSON
- [ ] 401（缺 Token / Token 无效）和 403（越权）行为不受影响
- [ ] traceId 全链路透传，WebFlux 环境下不丢失、不串号
- [ ] Sentinel 限流规则可从 Nacos 动态下发，30 秒内生效；Nacos 不可用时硬编码兜底生效

### 公共组件
- [ ] `mvn compile` 全量模块编译通过，无依赖版本冲突
- [ ] 引入 `common-core` 即可获得完整异常处理能力 + MQ 事件基类
- [ ] 引入 `common-redis` 即可注入 `RedisTemplate<String, Object>`
- [ ] 引入 `common-swagger` 即可访问 Swagger UI
- [ ] 引入 `common-feign` 即可使用全部 7 个服务的 Feign Client

### 消息与通知
- [ ] `BaseEvent` 子类自动填充 eventId/occurredAt/traceId/producer，JSON 序列化含全部信封字段
- [ ] `common-notification` 提供短信/微信订阅消息/语音呼叫统一接口，Mock 模式可用

### 规范合规
- [ ] `SystemErrorCode` 与《错误码及异常设计》2.2 节逐项一致
- [ ] 所有错误响应格式与《错误码及异常设计》1.1 节一致
- [ ] Feign 默认超时与《代码规范文档》二、一致（connect 2s / read 5s）
- [ ] 雪花 ID JSON 序列化与《代码规范文档》四、一致（Long → String）
- [ ] MQ 事件信封字段与《代码规范文档》二、一致

---

## 八、common-security 模块治理

### 背景

`common-security` 是全部微服务共用的安全认证模块（26 个 Java 文件），提供 JWT 签发/验签/刷新、Servlet 鉴权过滤器、SecurityContextHolder、Feign Token 透传、@RequireRole 角色 AOP 等核心能力。Gateway 的 [JwtAuthGlobalFilter](file:///e:/workspace/project/eldercare-platform/gateway/src/main/java/com/eldercare/gateway/filter/JwtAuthGlobalFilter.java) 直接依赖本模块的 `JwtTokenProvider`、`LoginUser`、`SecurityConstants`。

**当前存在四个治理问题：**

1. **双包结构混乱** — 存在 `com.eldercare.security.*`（System A：JWT/SecurityContextHolder/RequireRoleAspect）和 `com.eldercare.common.security.*`（System B：UserContext/UserInfo/FeignRequestInterceptor）两套并行的安全上下文体系，各有独立的 Feign 拦截器和 AutoConfiguration
2. **自动装配失效** — `com.eldercare.security.config.SecurityAutoConfiguration` 标注了 `@AutoConfiguration` 但无对应的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件，Spring Boot 3.x SPI 机制无法发现
3. **Feign 拦截器重复** — System A 的 `FeignAuthRequestInterceptor` 和 System B 的 `FeignRequestInterceptor` 都在做 Token + traceId 透传，功能重叠
4. **JwtTokenProvider 用明文密钥构造** — 生产环境中 SecretKey 通过字符串 `getBytes()` 构造，缺少密钥长度校验和安全的密钥管理方案提示

---

### 用户故事 15：消除 common-security 双包结构，统一到单一安全上下文体系

**作为** 基础架构负责人
**我想要** common-security 只保留一套安全上下文（`SecurityContextHolder` + `LoginUser`），删除重复的 `UserContext` + `UserInfo`
**以便于** 下游服务开发者不会困惑该用哪一套 API，Feign 拦截器也不会重复透传请求头

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-15.1** 包路径统一 | 所有源码统一到 `com.eldercare.security.*`，删除 `com.eldercare.common.security.*` 包下的全部文件 |
| **FS-15.2** 上下文统一 | 保留 `SecurityContextHolder.getLoginUser()`，删除 `UserContext` + `UserInfo` |
| **FS-15.3** Feign 拦截器合并 | 保留 `FeignAuthRequestInterceptor`（透传 Authorization + X-Trace-Id），删除 System B 的 `FeignRequestInterceptor` |
| **FS-15.4** WebMvc 拦截器迁移 | 将 `UserContextInterceptor` 的职责合并到 `JwtAuthenticationFilter`（已覆盖提取用户信息并设置上下文），删除 `UserContextInterceptor` |

#### 验收标准

- [ ] `com.eldercare.common.security.*` 包下不再有任何 `.java` 文件
- [ ] 全仓搜索 `UserContext.getUser()` 返回 0 结果
- [ ] 全仓搜索 `UserInfo` 返回 0 结果（测试代码除外）
- [ ] `FeignRequestInterceptor` 和 `FeignAuthRequestInterceptor` 只保留一个
- [ ] 单元测试 `SecurityContextHolderTest` 全部通过
- [ ] 单元测试 `JwtTokenProviderTest` 全部通过

---

### 用户故事 16：修复 Spring Boot 3.x 自动装配，确保引入即生效

**作为** 业务服务开发者
**我想要** 在 `pom.xml` 中引入 `common-security` 后，JWT 鉴权和 Feign 拦截器自动生效
**以便于** 不需要在启动类上添加 `@ComponentScan` 或手工 `@Import` 配置类

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-16.1** SPI 注册 | 创建 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，注册 `com.eldercare.security.config.SecurityAutoConfiguration` |
| **FS-16.2** 移除旧式配置 | `com.eldercare.common.security.config.SecurityAutoConfiguration`（`@Configuration`）在删除 System B 时一并移除 |
| **FS-16.3** 条件装配 | 已通过 `@ConditionalOnProperty(prefix="eldercare.security", name="enabled")` 和 `@ConditionalOnWebApplication` 控制开关，本次仅需补充 SPI 文件 |

#### 验收标准

- [ ] `common-security` jar 包中存在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [ ] 业务服务引入 `common-security` 后，不添加任何额外注解，`JwtAuthenticationFilter` 自动生效
- [ ] 设置 `eldercare.security.enabled=false` 后，`JwtTokenProvider` Bean 不注册
- [ ] 非 Web 应用引入 `common-security` 不触发自动配置

---

### 用户故事 17：JwtTokenProvider 密钥安全加固

**作为** 安全审计人
**我想要** JWT 签名密钥支持从环境变量或外部密钥管理系统读取，且有最小长度校验
**以便于** 生产部署时不会使用硬编码的演示密钥，降低密钥泄露风险

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-17.1** 密钥长度校验 | 构造 `JwtTokenProvider` 时校验 secret 长度 ≥ 32 字符（HS256 最低要求），不满足时启动失败并给出明确提示 |
| **FS-17.2** 环境变量支持 | `SecurityProperties.secret` 默认值改为 `${ELDERCARE_JWT_SECRET:}`（强制从环境变量读取），去掉硬编码的演示密钥 |
| **FS-17.3** 启动警告 | 当 secret 使用默认值或长度不足时，以 WARN 级别日志输出安全提示 |

#### 验收标准

- [ ] 不设置 `ELDERCARE_JWT_SECRET` 环境变量且不配置 `eldercare.security.secret` 时，启动抛 `IllegalStateException`，消息包含"密钥长度不足"
- [ ] 设置 64 字符密钥后正常启动
- [ ] 设置 16 字符密钥时启动失败，异常消息明确提示"最小 32 字符"
- [ ] 生产密钥来源在日志中不输出明文

---

### 用户故事 18：JwtAuthenticationFilter 补充 traceId 响应头

**作为** 前端开发者 / 运维人员
**我想要** 401 鉴权拒绝响应中也包含 `traceId`
**以便于** 前端报错时能拿 traceId 找后端排查，与 Gateway 的 401 行为一致

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-18.1** traceId 注入 | `JwtAuthenticationFilter.writeErrorResponse()` 返回的 401 JSON 中包含 `traceId` 字段，来源为 `TraceContext.currentTraceId()` |
| **FS-18.2** 与 Gateway 对齐 | 响应格式与 Gateway 的 `JwtAuthGlobalFilter.writeUnauthorized()` 保持一致：`{"code":110001, "msg":"...", "data":null, "traceId":"..."}` |

#### 验收标准

- [ ] 不带 Token 访问受保护接口 → 401 + JSON 中包含非空 `traceId`
- [ ] 带过期 Token 访问 → 401 + JSON 中包含非空 `traceId`
- [ ] Gateway 透传 traceId 到下游后，下游 401 响应中的 traceId 与网关一致

---

## 九、common-audit 审计增强

### 背景

`common-audit` 模块通过 `@AuditLog` 注解 + `AuditLogAspect` AOP 提供了操作审计日志能力（5 个 Java 文件），自动记录操作人、IP、URL、耗时、入参、出参等信息。

**当前缺口**：审计日志中缺少 `traceId`，出问题时无法将审计记录与请求链路关联。同时使用了独立 `new ObjectMapper()` 而非注入容器管理的 ObjectMapper，无法享受 JacksonConfig 的 Long→String 序列化等全局配置。

---

### 用户故事 19：审计日志可关联全链路 traceId

**作为** 运维/开发人员
**我想要** 每条 `@AuditLog` 注解产生的审计日志中包含 `traceId` 字段
**以便于** 通过 traceId 将"用户做了什么操作"与"请求经过了哪些服务"串联起来

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-19.1** traceId 记录 | `AuditLogAspect` 在构建 `auditMap` 时调用 `TraceContext.currentTraceId()` 并写入 `traceId` 字段 |
| **FS-19.2** ObjectMapper 注入 | `AuditLogAspect` 的 ObjectMapper 改为通过构造器注入（使用 Spring 容器管理的 Bean），不再 `new ObjectMapper()` |
| **FS-19.3** 空值降级 | traceId 为 null 时记录 "N/A"，不抛异常、不阻断业务 |

#### 验收标准

- [ ] 审计日志 JSON 中包含 `"traceId": "a1b2c3d4..."` 字段
- [ ] 同一请求的 Gateway 日志 → 业务日志 → 审计日志中 traceId 一致
- [ ] 未经过 Gateway（如单元测试直接调用）时 traceId 为 "N/A"

---

## 十、common-file 存储扩展

### 背景

`common-file` 模块（6 个 Java 文件）提供了 `FileStorageService` 接口和 `LocalFileStorageServiceImpl` 本地文件存储实现。当前仅支持本地存储，且 `FileAutoConfiguration` 使用旧式 `@Configuration` 注解（非 Spring Boot 3.x `@AutoConfiguration`），无 SPI 注册文件。

---

### 用户故事 20：common-file 支持 COS 云存储，自动装配可被发现

**作为** 业务服务开发者
**我想要** 通过配置 `eldercare.file.type=cos` 即可切换到腾讯云 COS 存储，且引入 `common-file` 后文件存储能力自动生效
**以便于** 本地开发用本地存储，线上环境用 COS，无需改代码

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-20.1** COS 实现 | 新增 `CosFileStorageServiceImpl`，实现 `FileStorageService` 接口，封装腾讯云 COS Java SDK，支持上传/下载/预览签名URL/删除 |
| **FS-20.2** 配置扩展 | `FileProperties` 新增 `CosConfig` 嵌套配置（secretId / secretKey / region / bucketName），通过 `@ConditionalOnProperty(type=cos)` 切换 |
| **FS-20.3** 自动装配升级 | `FileAutoConfiguration` 从 `@Configuration` 升级为 `@AutoConfiguration`，创建 `AutoConfiguration.imports` SPI 文件 |
| **FS-20.4** Mock 隔离 | `MockFileController` 仅在 `type=local` 时注册，生产环境不暴露本地文件预览接口 |

#### 验收标准

- [ ] 引入 `common-file` 后不添加任何额外注解，`FileStorageService` Bean 自动注入
- [ ] 配置 `eldercare.file.type=cos` + COS 凭证后，文件上传到腾讯云 COS Bucket
- [ ] 配置 `eldercare.file.type=local` 后，文件存储到本地磁盘
- [ ] COS 模式下不暴露 Mock 预览 Controller

---

## 十一、~~common-notification 邮件通知实现~~（已废弃）

> ⚠️ 本节（US21）已废弃。原方案将 `common-notification` 定位为邮件模块，与《微服务架构方案》四、公共组件库的定义（"阿里云短信、微信订阅消息、语音呼叫封装"）矛盾。现已回归架构方案，`common-notification` 的统一通知能力见 US13。邮件通知如有需要，列入二期规划。

---

## 十二、MQ 运行时基础设施

### 背景

PRD US12 已实现 `BaseEvent` 事件信封基类（eventId / eventType / schemaVersion / occurredAt / traceId / producer），解决了"消息长什么样"的问题。

但**"消息怎么发、怎么收、消费失败怎么办"完全空白**：项目中无 RocketMQ 依赖、无 Producer 抽象、无 Consumer 基类、无幂等消费机制、无死信处理策略。各业务服务如果要在当前状态下接入 MQ，需要各自从头搭建。

---

### 用户故事 22：服务间可通过统一的 Producer 发送可靠异步消息

**作为** 业务服务开发者
**我想要** 注入一个 `MQProducer` 工具类，传入 `BaseEvent` 子类即可发送 RocketMQ 消息
**以便于** 我不需要关心 RocketMQ NameServer 地址、Producer 分组、发送重试、序列化等底层细节

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-22.1** Producer 封装 | 新增 `MQProducer` 类，封装 RocketMQ `DefaultMQProducer`，提供 `send(String topic, BaseEvent event)` / `sendAsync(String topic, BaseEvent event)` / `sendDelay(String topic, BaseEvent event, int delayLevel)` |
| **FS-22.2** 自动配置 | `RocketMQAutoConfiguration` 根据 `eldercare.rocketmq.name-server` 配置自动创建 `MQProducer` Bean；未配置时跳过注册 |
| **FS-22.3** 发送重试 | 同步发送失败自动重试 2 次（RocketMQ SDK 内置），异步发送失败回调日志记录 |
| **FS-22.4** JSON 序列化 | `BaseEvent` 使用 Jackson 序列化为 JSON 字符串作为消息体，消息 key 使用 `eventId`（支持顺序消息和幂等键） |
| **FS-22.5** 事务消息 | 提供 `sendTransaction(String topic, BaseEvent event, TransactionListener listener)` 方法，支持 RocketMQ 半消息 + 本地事务执行 + 回查机制。`TransactionListener` 为接口（`executeLocalTransaction` / `checkLocalTransaction`），由各业务服务实现具体的本地事务逻辑 |
| **FS-22.6** traceId 写入消息 Header | 发送时除消息体信封含 `traceId` 外，**同时**将 traceId 写入 RocketMQ Message 的 user properties（key = `X-Trace-Id`），值取自 `TraceContext.currentTraceId()`。消费方可在反序列化前从 Header 提取 traceId 设置到 MDC（见 US28 FS-28.3），使消费日志在解析消息体之前即可关联链路 |

> 注：事务消息的典型场景是 `alert-service` 的"预警确认→生成工单"链路（依据《代码规范》二："关键事件须与本地数据库变更原子衔接"）。公共组件提供能力，业务服务决定是否使用。

#### 验收标准

- [ ] 配置 `eldercare.rocketmq.name-server=127.0.0.1:9876` 后，`MQProducer` Bean 自动创建
- [ ] `mqProducer.send("topic-care", event)` → RocketMQ Console 可见消息，消息体为 BaseEvent JSON
- [ ] 未配置 NameServer 时启动不报错，首次发送时抛 `BizException`
- [ ] 消息 key 与 eventId 一致
- [ ] `mqProducer.sendTransaction("topic-alert", event, listener)` → 本地事务成功时消息投递，本地事务失败时消息回滚
- [ ] RocketMQ Broker 回查时调用 `listener.checkLocalTransaction()`，返回正确的事务状态

---

### 用户故事 23：服务间消费 MQ 消息有统一的幂等和死信处理

**作为** 业务服务开发者
**我想要** 继承 `BaseConsumer` 抽象类后只需实现 `processEvent` 方法，框架自动处理幂等校验和消费失败重试
**以便于** 我不会因重复消费导致数据错误，消费失败的消息也能在死信队列中找到

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-23.1** Consumer 基类 | 新增 `BaseConsumer<T extends BaseEvent>` 抽象类，封装 RocketMQ `DefaultMQPushConsumer` 的订阅、反序列化、幂等校验、异常处理 |
| **FS-23.2** 幂等消费（双层） | **第一层（Redis 快速拦截）**：以消息 key（= eventId）在 Redis 中设置消费标记（TTL 24h），重复消息直接 ACK 并记录 WARN 日志，减少 DB 查询压力。**第二层（DB 最终保障）**：维护 `mq_consumed(event_id VARCHAR(64) PK, topic VARCHAR(128), consumed_at TIMESTAMP)` 消费记录表，通过数据库唯一约束保证最终正确性。Redis 宕机或标记过期时，DB 层兜底防重复消费 |
| **FS-23.3** 消费重试 | 消费失败（抛异常）时，RocketMQ 自动重试 16 次（递增间隔）；超过重试次数后进入 DLQ（死信队列） |
| **FS-23.4** 死信告警 | DLQ 消费者默认记录 ERROR 日志（含 eventId、异常堆栈），预留告警钩子供运维对接 |
| **FS-23.5** 消费日志 | 消费开始/成功/失败均记录 INFO/ERROR 日志，包含 topic、tag、eventId、traceId、耗时 |

**消费流程：**
```
收到消息 → 反序列化 BaseEvent → Redis 检查 eventId 是否已消费
  ├─ 已消费（Redis 命中）→ ACK + WARN 日志（幂等拦截）
  └─ 未消费 / Redis 不可用 → DB 查询 mq_consumed 表
       ├─ DB 已存在 → ACK + WARN 日志 + 补写 Redis 标记
       └─ DB 不存在 → processEvent(event) 子类业务逻辑
            ├─ 成功 → DB 插入消费记录 + Redis 标记 + ACK
            └─ 失败 → 抛异常（RocketMQ 自动重试 → 最终进入 DLQ）
```

> 设计依据：《代码规范文档》二—"幂等以数据库唯一约束、消费记录表或状态机条件更新为准。Redis 锁仅用于并发优化，不能作为唯一正确性保障；去重保留期须覆盖补偿窗口。"

#### 验收标准

- [ ] 子类继承 `BaseConsumer<CareEvent>` 后通过 `@Component` 注册，启动后自动订阅对应 Topic
- [ ] 同一条消息发送两次，第一次正常消费，第二次幂等拦截（日志含 "duplicate message"）
- [ ] 消费抛异常后，RocketMQ Console 可见重试次数递增，16 次后消息进入 `%DLQ%` 前缀的死信队列
- [ ] 消费成功/失败日志均包含 eventId 和 traceId
- [ ] Redis 不可用时，DB 消费记录表仍能防重复消费
- [ ] `mq_consumed` 表建表 SQL 模板随公共组件提供

---

## 十三、Gateway 增强能力

### 背景

《微服务架构方案》3.1 节功能清单中列出了 Token 黑名单、全量请求日志、SOS 高优先级保障等能力，当前 PRD 尚未覆盖。同时，网关自身的健康检查和优雅下线策略也需要明确定义。

---

### 用户故事 24：登出或封禁后 Token 立即失效

**作为** 安全管理员
**我想要** 用户登出或被封禁后，其持有的 JWT Token 立即无法通过网关鉴权
**以便于** 不需要等待 Token 自然过期（最长 2 小时），降低 Token 泄露后的风险窗口

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-24.1** 黑名单检查 | `JwtAuthGlobalFilter` 在 JWT 密码学验签通过后，额外检查 Redis 中是否存在该 Token 的黑名单记录。命中则返回 401 + `{"code":110001}` |
| **FS-24.2** 登出写入 | `service-auth` 的登出接口将 Token 的 JTI（JWT ID）写入 Redis，key 格式 `token:blacklist:{jti}`，TTL = Token 剩余有效期 |
| **FS-24.3** 封禁写入 | 封禁用户时按 userId 维度写入 `token:blacklist:user:{userId}`（无 TTL，手动删除解封），该用户所有 Token 立即失效 |
| **FS-24.4** 响应式 Redis | Gateway 是 WebFlux 架构，使用 `spring-boot-starter-data-redis-reactive` 的 `ReactiveRedisTemplate` 进行非阻塞 Redis 查询，不使用同步 `RedisTemplate` |
| **FS-24.5** 性能约束 | Redis 黑名单查询增加的延迟 ≤ 2ms（P99）；Redis 不可用时降级为仅做 JWT 密码学验签（WARN 日志记录降级事件），不阻断请求 |

#### 验收标准

- [ ] 用户登出后，使用同一 Token 访问网关 → 401 + `{"code":110001}`
- [ ] 封禁用户后，该用户所有 Token 立即失效 → 401
- [ ] 未登出的正常 Token 不受影响 → 200
- [ ] Redis 宕机时，网关降级为仅 JWT 验签，不返回 500（WARN 日志记录）
- [ ] 黑名单 Redis key 在 Token 过期后自动清理（TTL 机制）

---

### 用户故事 25：每个请求都有完整的访问日志可供排查

**作为** 运维/开发人员
**我想要** 网关记录每个入站请求的关键信息（路径、方法、耗时、状态码）
**以便于** 出现慢请求、异常状态码或流量异常时能快速定位

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-25.1** 日志 Filter | 新增 `AccessLogGlobalFilter`（order = `Ordered.HIGHEST_PRECEDENCE`），确保记录完整请求耗时（从进入网关到响应写出） |
| **FS-25.2** 记录字段 | traceId、HTTP method、请求路径（不含 query string 中的敏感参数）、客户端真实 IP（从 `X-Forwarded-For` 提取）、响应状态码、耗时（ms） |
| **FS-25.3** 日志级别 | 正常请求（耗时 < 2s）→ INFO；慢请求（耗时 ≥ 2s）→ WARN；5xx 响应 → ERROR |
| **FS-25.4** 不记录内容 | 不记录请求体、响应体、Authorization Header、Cookie（避免敏感数据泄露 + 性能开销） |
| **FS-25.5** 日志格式 | 使用结构化日志（SLF4J MDC），便于 ELK/Loki 检索。格式：`[traceId] method path status duration_ms client_ip` |

#### 验收标准

- [ ] 任意请求完成后，Gateway 日志中可见一行包含 traceId、method、path、status、duration 的访问记录
- [ ] 耗时超过 2s 的请求日志级别为 WARN
- [ ] 日志中不包含 Token、密码、请求体等敏感信息
- [ ] 100 并发下日志不串号（每条日志的 traceId 与对应请求一致）

---

### 用户故事 26：SOS 链路在网关层不被限流或熔断阻断

**作为** 安全架构负责人
**我想要** SOS/预警相关请求在网关层享有最高通行优先级，不因限流或熔断被拒绝
**以便于** 满足 SOS 端到端 T≤10s 的硬指标（依据《微服务架构方案》3.3 节）

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-26.1** 高限流阈值 | `/alert/**` 路由 Sentinel QPS 阈值设为 500（已在 US1 FS-1.3 定义），远高于通用路由的 200 |
| **FS-26.2** 不配置熔断 | `/alert/**` 路由不配置 Sentinel 熔断降级规则。其他路由可配置 503 降级，但 SOS 路径宁可慢也不能拒绝 |
| **FS-26.3** WebSocket 独立 | WebSocket 告警推送路由（`ws/alert/**`）不受 HTTP 限流计数器影响，由 `WebSocketConnectionLimitFilter` 独立管控 |
| **FS-26.4** 职责边界声明 | SOS 端到端 T≤10s 的保障主要依赖：① `alert-service` 的专属线程池（Bulkhead）；② RocketMQ 可靠投递（同步发送+重试）；③ 设备心跳超时二次兜底。网关层职责是"不限流、不熔断、不阻断" |

#### 验收标准

- [ ] `/alert/**` 路由在 Sentinel Dashboard 中无熔断规则配置
- [ ] 通用路由触发熔断时，`/alert/**` 不受影响
- [ ] 压测 `/alert/sos` 至 400 QPS 时全部正常通过（未触发 429）
- [ ] 压测 `/alert/sos` 至 600 QPS 时超出部分返回 429（500 阈值生效）

---

### 用户故事 27：网关支持健康检查和优雅下线

**作为** 运维人员
**我想要** 能通过健康检查接口监控网关状态，且重启/更新时不会丢失存量请求
**以便于** Nacos 能正确感知网关存活状态，滚动更新时前端不会收到连接中断错误

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-27.1** 健康端点 | 暴露 `/actuator/health`（供 Nacos 心跳和负载均衡健康检查），不暴露其他 actuator 端点（`/actuator/env`、`/actuator/beans` 等关闭，安全考虑） |
| **FS-27.2** 优雅停机 | 收到 SIGTERM 后：① 从 Nacos 注销（停止接收新请求）→ ② 等待存量 HTTP 请求处理完毕（超时 30s）→ ③ 向所有活跃 WebSocket 连接发送 Close frame（code=1001, reason="server shutting down"）→ ④ 关闭连接，停止进程 |
| **FS-27.3** 下游健康 | 依赖 Nacos 服务发现的健康检查机制，Gateway 不主动探测下游。Spring Cloud LoadBalancer 自动剔除不健康实例，路由到健康实例 |
| **FS-27.4** 就绪探针 | 启动完成前（路由规则未加载、Nacos 未注册成功），`/actuator/health` 返回 `OUT_OF_SERVICE`，防止流量提前进入 |

#### 验收标准

- [ ] `GET /actuator/health` → 200 + `{"status":"UP"}`（正常运行时）
- [ ] `GET /actuator/env` → 404（未暴露）
- [ ] 执行 `kill -15 <pid>` 后，存量请求正常完成，WebSocket 客户端收到 Close frame
- [ ] 优雅停机过程中新请求被拒绝（Nacos 已注销，流量不再路由到此实例）
- [ ] 启动过程中 `/actuator/health` 返回 `OUT_OF_SERVICE`，Nacos 注册完成后变为 `UP`

---

### 用户故事 29：四端前端跨域请求被网关统一放行

**作为** 前端开发者（管理端 Web / 服务端 Web / 长者端小程序 / 亲属端小程序）
**我想要** 网关统一处理 CORS 跨域，前端不需要各自配置跨域
**以便于** 浏览器跨域请求（管理端、服务端 Web）能正常携带 Token 访问网关，且不被重复的 CORS 配置冲突困扰

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-29.1** 统一 CORS 处理 | 网关通过全局 `CorsWebFilter` 统一处理跨域，下游业务服务**不再各自配置 CORS**（避免重复 `Access-Control-Allow-Origin` 头导致浏览器报错） |
| **FS-29.2** 允许 Origin | 通过 `eldercare.security.cors.allowed-origins` 配置允许的源（支持多端域名 + 本地开发 `http://localhost:*`），不使用 `*` 通配（因需携带 Credentials） |
| **FS-29.3** 允许方法与头 | `allowed-methods` = GET/POST/PUT/DELETE/OPTIONS；`allowed-headers` = `*`（含 Authorization、X-Trace-Id、Content-Type）；`exposed-headers` = `X-Trace-Id`（前端可读取 traceId 用于报错排查） |
| **FS-29.4** Credentials | `allow-credentials = true`，支持跨域携带 Authorization Token |
| **FS-29.5** 预检缓存 | `max-age = 3600`，OPTIONS 预检请求结果缓存 1 小时，减少预检次数 |
| **FS-29.6** 小程序兼容 | 微信小程序不存在浏览器同源策略限制，CORS 配置主要服务于 Web 端（管理端、服务端）；小程序端的请求不受 CORS 影响，配置对其透明 |

#### 验收标准

- [ ] 管理端 Web（`http://localhost:5173`）跨域调用 `http://localhost:9999/admin/**` 成功，响应含正确的 `Access-Control-Allow-Origin`
- [ ] 跨域请求携带 `Authorization` 头时，`Access-Control-Allow-Credentials: true` 生效
- [ ] OPTIONS 预检请求返回 200 且含 `Access-Control-Max-Age: 3600`
- [ ] 前端 JS 可读取响应头 `X-Trace-Id`（`exposed-headers` 生效）
- [ ] 下游业务服务响应中不出现重复的 `Access-Control-Allow-Origin` 头
- [ ] 配置 `eldercare.security.cors.allowed-origins` 新增域名后，该域名跨域请求被放行

---

## 十四、公共组件补充

### 用户故事 28：异步线程池中 traceId 不丢失

**作为** 业务服务开发者
**我想要** 在 `@Async`、`CompletableFuture.supplyAsync()` 或自定义线程池中执行的任务能自动继承调用方的 traceId
**以便于** 异步任务的日志能与主请求链路关联，不需要手动传递 traceId

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-28.1** 装饰器工具 | 在 `common-core` 中提供 `TraceableExecutorDecorator`，包装 `Executor` / `ExecutorService`，在任务提交时捕获当前 MDC 上下文（含 traceId），在任务执行时恢复到子线程 MDC，执行完毕后清理 |
| **FS-28.2** @Async 集成 | 提供 `TraceableAsyncConfigurer`（实现 `AsyncConfigurer`），业务服务继承后 `@Async` 方法自动使用装饰后的线程池 |
| **FS-28.3** MQ 消费线程 | `BaseConsumer`（US23）在消费消息时设置 traceId 到当前线程 MDC，**提取优先级**：① 优先从 RocketMQ Message Header（user properties `X-Trace-Id`，由生产方 FS-22.6 写入）提取；② Header 缺失时回退到反序列化后消息体信封的 `traceId` 字段；③ 两者皆无则生成新 traceId 并记 WARN 日志。消费完毕后在 finally 中清理 MDC |
| **FS-28.4** 清理保障 | 所有 MDC 设置必须在 finally 块中清理，防止线程池复用导致 traceId 串号 |

#### 验收标准

- [ ] 业务服务中 `@Async` 方法的日志包含与调用方相同的 traceId
- [ ] `CompletableFuture.supplyAsync(task, traceableExecutor)` 中 `TraceContext.currentTraceId()` 返回正确值
- [ ] 线程池复用场景下，前一个任务的 traceId 不会泄漏到下一个任务
- [ ] MQ 消费日志中的 traceId 与生产方一致

---

## 十五、Gateway 与 service-auth 职责边界

> 本节明确网关和认证服务之间的职责划分，避免重复实现或职责真空。

| 职责 | 归属 | 说明 |
|------|------|------|
| JWT 验签（密码学校验） | **Gateway** | 无状态操作，`JwtAuthGlobalFilter` 中完成，不依赖数据库 |
| RBAC 角色路径拦截 | **Gateway** | 无状态操作，配置驱动（yml / Nacos） |
| Token 黑名单检查 | **Gateway** | 依赖 Redis（ReactiveRedisTemplate），见 US24 |
| 请求路由与负载均衡 | **Gateway** | Spring Cloud Gateway + Nacos Discovery |
| 限流与熔断 | **Gateway** | Sentinel Gateway，见 US1 FS-1.3 |
| 登录（校验用户名密码，签发 Token） | **service-auth** | 业务 CRUD，调用 `JwtTokenProvider` 签发 Token Pair |
| Token 刷新 | **service-auth** | 验证 refresh token，签发新 access + refresh token |
| 登出（Token 加入黑名单） | **service-auth** | 写 Redis 黑名单（`token:blacklist:{jti}`） |
| 用户注册 / 信息管理 | **service-auth** | 业务 CRUD |
| 封禁用户 | **service-auth**（执行）+ **service-operation**（发起） | operation 管理端发起封禁指令，auth 执行黑名单写入 |

**设计原则**：Gateway 不做任何用户业务逻辑（不查数据库、不校验密码、不管理用户状态），只做三件事——"验证 Token 是不是合法的"、"验证 Token 是不是被吊销了"、"验证角色有没有权限访问这个路径"。

---

## 十六、Feign 使用约束

> 本节依据《微服务架构方案》七、《代码规范文档》二、《错误码及异常设计》五，明确 Feign 同步调用的允许和禁止边界。

### 允许 Feign 同步调用的场景

- 只读查询：如 `care-service` 查询 `operation-service` 的排班信息、查询长者合同详情
- 非 P0 链路的数据聚合：如 `family-service` 聚合 `vital-service` 数据生成日报
- 认证相关：下游服务验证 Token 有效性（调用 `AuthClient.validateToken`）
- 调用方能明确反馈"暂不可用"或展示带时间戳的缓存数据

### 禁止 Feign 同步调用的场景

- 任何跨服务写操作或状态变更（必须走 RocketMQ 异步事件）
- P0 链路：SOS 触发 → 告警 → 工单生成（全链路 MQ，依据架构方案 7.1 节）
- 扣费、库存变更、核销、权限判定（禁止以 fallback 默认值继续执行）
- 需要分布式事务的场景（须走 Seata TCC/XA 或 Saga，且不得用于 P0 链路）

### 技术约束

| 约束项 | 要求 |
|--------|------|
| 返回值 | FeignClient 方法返回 `RemoteDTO`（或 `List<RemoteDTO>`），**不包装 `R<T>`** |
| FallbackFactory | 全部 7 个 Client 的 FallbackFactory 统一抛出 `RemoteCallException`，禁止返回 null / 空集合 / 默认值 |
| 超时 | 默认 connect 2s / read 5s，仅在 Nacos 配置中心维护 |
| 例外登记 | 超出默认值 3 倍的超时配置必须登记：调用方、超时预算、重试条件、降级行为 |
| 契约位置 | 全部 Feign 接口、RemoteDTO、FallbackFactory 统一放在 `common-feign` 模块；服务端 Controller 不依赖该模块 |
| AI 调用例外 | AI 推理（WebClient）是唯一的同步例外，受超时+熔断保护，P0 意图识别有同进程规则引擎兜底 |

---

## 十七、契约测试与质量门禁

### 背景

《代码规范文档》五.1 明确要求："公开 API、Feign 契约、MQ 事件和错误码变更必须有契约测试；状态机、幂等、重试、事务消息和异常映射必须有自动化测试。"当前 PRD 各 US 定义了验收标准，但缺少统一的测试与质量门禁要求。本节作为基础架构层面的横向约束，适用于 gateway 和全部 common 模块。

---

### 用户故事 30：公共组件与网关的关键行为有自动化测试守护

**作为** 基础架构负责人
**我想要** gateway 和 common 模块的关键行为（鉴权、异常映射、Feign 契约、MQ 事件、幂等、事务消息）都有自动化测试，且 CI 强制校验
**以便于** 后续迭代不会破坏已稳定的基础设施行为，各业务服务可以放心依赖公共组件

#### 功能规格

| 功能点 | 描述 |
|--------|------|
| **FS-30.1** 异常映射测试 | `GlobalExceptionHandler` 的全部异常类型（US5 表格中 10 类）→ HTTP 状态 + 业务码的映射必须有单元测试覆盖，断言 code/msg/httpStatus/traceId 四要素 |
| **FS-30.2** 网关过滤器测试 | `JwtAuthGlobalFilter`、`RbacAuthGlobalFilter`、`TraceIdGlobalFilter`、`AccessLogGlobalFilter` 使用 WebFlux `WebTestClient` / `GatewayFilter` 单元测试框架，覆盖 401/403/traceId 透传/黑名单场景 |
| **FS-30.3** Feign 契约测试 | `common-feign` 的每个 Client 接口签名（方法名、参数、返回 RemoteDTO 结构）有契约测试，防止接口被无意修改导致消费方编译/运行失败 |
| **FS-30.4** MQ 事件契约测试 | `BaseEvent` 子类的 JSON 序列化结果必须包含全部 6 个信封字段（eventId/eventType/schemaVersion/occurredAt/traceId/producer），用序列化断言守护，防止字段被误删 |
| **FS-30.5** 幂等与重试测试 | `BaseConsumer` 的双层幂等（Redis + DB）、消费重试、死信流转必须有自动化测试（可用嵌入式 Redis + H2/测试容器） |
| **FS-30.6** 事务消息测试 | `MQProducer.sendTransaction` 的本地事务成功/失败/回查三种路径必须有测试覆盖 |
| **FS-30.7** 错误码唯一性校验 | CI 流水线增加错误码校验步骤：全仓扫描 `IErrorCode` 实现，断言 code 六位、号段归属正确、无重复（依据《错误码及异常设计》二） |
| **FS-30.8** 测试约束 | 禁止无断言的测试、禁止 `Thread.sleep` 依赖时序的脆弱测试；集成测试使用 Testcontainers 或 Mock，不依赖外部真实中间件（CI 环境无 Nacos/RocketMQ） |

#### 验收标准

- [ ] `common-core` 的 `GlobalExceptionHandler` 测试覆盖 US5 表格全部 10 类异常
- [ ] `common-feign` 每个 Client 有契约测试，修改接口签名时测试失败
- [ ] `BaseEvent` 序列化测试断言 6 个信封字段齐全
- [ ] `BaseConsumer` 幂等/重试/死信有自动化测试
- [ ] CI 执行 `mvn test` 时错误码唯一性校验通过
- [ ] 全部测试无 `Thread.sleep` 时序依赖、无空断言

---

## 十八、更新后的实施优先级

```
第一优先级（已实现 ✅）
  ├── [Gateway] US1-4 路由 + 错误处理 + traceId + Sentinel Nacos
  ├── [common-core] US4-7 异常 + 错误码 + Jackson + BaseEvent
  ├── [公共模块] US8-11 common-redis/swagger/feign
  └── [工程治理] US14 Maven 模块注册

第二优先级（安全模块治理，可并行）
  ├── [common-security] US15 双包结构统一
  ├── [common-security] US16 自动装配修复
  ├── [common-security] US17 密钥安全加固
  └── [common-security] US18 401 响应补充 traceId

第三优先级（Gateway 增强 + 模块补全，可并行）
  ├── [Gateway] US24 Token 黑名单（依赖 common-redis reactive）
  ├── [Gateway] US25 全量请求日志
  ├── [Gateway] US26 SOS 路径高优先级保障
  ├── [Gateway] US27 健康检查与优雅下线
  ├── [Gateway] US29 CORS 跨域统一配置
  ├── [common-audit] US19 审计日志 traceId 串联
  ├── [common-file] US20 COS 存储 + 自动装配升级
  ├── [common-notification] US13 短信/微信/语音通知实现（Mock + 真实通道）

第四优先级（MQ 基础设施 + 异步透传）
  ├── [common-core] US22 MQProducer 生产者封装（含事务消息）
  ├── [common-core] US23 BaseConsumer 消费者基类（DB+Redis 双层幂等）
  └── [common-core] US28 异步线程池 traceId 透传

第五优先级（质量门禁 + 配置规范化，贯穿收尾）
  ├── [测试] US30 契约测试与质量门禁（异常映射/Feign/MQ/幂等/事务消息）
  ├── [规范] 附录 A Nacos 配置项清单落地（统一 dataId/group）
  └── [规范] 附录 B Redis Key 命名规范落地（统一 eldercare: 前缀）
```

> 注：US24 依赖 `spring-boot-starter-data-redis-reactive`（Gateway 独立引入，不依赖 common-redis 的同步 RedisTemplate）。US23 的 DB 幂等层需要各业务服务建 `mq_consumed` 表，公共组件提供建表 SQL 模板。US28 依赖 US23（MQ 消费线程的 traceId 透传在 BaseConsumer 中实现）。US30 契约测试应随各 US 的实现同步编写，不积压到最后；附录 A/B 的配置规范在第二优先级起即应遵循。

---

## 十九、总体验收清单

### Gateway 统一入口
- [ ] 8 条 HTTP 路由全部可访问，限流三级分档生效（登录 20 / SOS 500 / 通用 200）
- [ ] 6 种错误场景（404/405/415/502/503/500）均返回统一 JSON
- [ ] 401（缺 Token / Token 无效 / Token 已吊销）和 403（越权）行为不受影响
- [ ] traceId 全链路透传，WebFlux 环境下不丢失、不串号
- [ ] Sentinel 限流规则可从 Nacos 动态下发，30 秒内生效；Nacos 不可用时硬编码兜底生效
- [ ] Token 黑名单：登出/封禁后 Token 立即失效，Redis 不可用时降级为仅 JWT 验签
- [ ] 访问日志：每个请求有 INFO/WARN/ERROR 级别的结构化日志
- [ ] SOS 路径不配置熔断，限流阈值 500
- [ ] 优雅停机：存量请求完成 + WebSocket Close frame + Nacos 注销

### 公共组件
- [ ] `mvn compile` 全量模块编译通过，无依赖版本冲突
- [ ] 引入 `common-core` 即可获得完整异常处理能力 + MQ 事件基类 + 异步 traceId 工具
- [ ] 引入 `common-redis` 即可注入 `RedisTemplate<String, Object>`
- [ ] 引入 `common-swagger` 即可访问 Swagger UI
- [ ] 引入 `common-feign` 即可使用全部 7 个服务的 Feign Client（返回 RemoteDTO，不包装 R<T>）
- [ ] 全部 FallbackFactory 统一抛 RemoteCallException

### 消息与通知
- [ ] `BaseEvent` 子类自动填充 eventId/occurredAt/traceId/producer，JSON 序列化含全部信封字段
- [ ] `MQProducer` 支持同步/异步/延迟/事务四种发送模式
- [ ] `BaseConsumer` 幂等双层（Redis 快速拦截 + DB 唯一约束）生效
- [ ] 消费失败 16 次后消息进入死信队列，日志记录 eventId + 异常堆栈
- [ ] `common-notification` 提供短信/微信订阅消息/语音呼叫统一接口，Mock/真实模式可切换

### 规范合规
- [ ] `SystemErrorCode` 与《错误码及异常设计》2.2 节逐项一致（含 100009 补充登记）
- [ ] 所有错误响应格式与《错误码及异常设计》1.1 节一致
- [ ] 参数校验失败响应包含 field/reason/rejectedValue（脱敏）
- [ ] Feign 默认超时与《代码规范文档》二.5 一致（connect 2s / read 5s）
- [ ] 雪花 ID JSON 序列化与《代码规范文档》四.1 一致（Long → String）
- [ ] MQ 事件信封字段与《代码规范文档》二 一致
- [ ] 幂等机制与《代码规范文档》二 一致（DB 为最终保障，Redis 仅并发优化）
- [ ] 异步线程池 traceId 透传与《代码规范文档》三.4 一致

### 契约测试与质量门禁
- [ ] `GlobalExceptionHandler` 测试覆盖 US5 全部 10 类异常映射
- [ ] `common-feign` 每个 Client 有契约测试守护接口签名
- [ ] `BaseEvent` 序列化测试断言 6 个信封字段齐全
- [ ] CI 错误码唯一性校验通过（六位、号段归属、无重复）
- [ ] CORS 配置生效，下游服务无重复 `Access-Control-Allow-Origin` 头

---

## 附录 A、Nacos 配置项清单

> 本附录列出 gateway 和 common 模块涉及的全部 Nacos 配置项，统一 dataId / group 命名，避免各服务各写各的。配置优先级：Nacos 共享配置 > 各服务私有配置 > 本地 bootstrap.yml 兜底。

**命名约定：**
- Group 统一使用 `DEFAULT_GROUP`
- 共享配置 dataId：`eldercare-common.yaml`（全部服务共享）
- 网关私有配置 dataId：`service-gateway.yaml`
- 各服务私有配置 dataId：`service-{name}.yaml`

### A.1 共享配置（eldercare-common.yaml）

| 配置项 | 默认值 | 说明 | 关联 US |
|--------|--------|------|---------|
| `eldercare.security.secret` | `${ELDERCARE_JWT_SECRET}` | JWT 签名密钥（强制环境变量注入，≥32 字符） | US17 |
| `eldercare.security.enabled` | `true` | 安全模块开关 | US16 |
| `eldercare.security.token.access-expiration` | `7200` | access token 有效期（秒） | US17 |
| `eldercare.security.token.refresh-expiration` | `604800` | refresh token 有效期（秒） | US17 |
| `spring.cloud.openfeign.client.config.default.connect-timeout` | `2000` | Feign 默认连接超时（ms） | US10 |
| `spring.cloud.openfeign.client.config.default.read-timeout` | `5000` | Feign 默认读取超时（ms） | US10 |
| `spring.cloud.openfeign.client.config.default.logger-level` | `basic` | Feign 日志级别 | US10 |
| `eldercare.rocketmq.name-server` | `127.0.0.1:9876` | RocketMQ NameServer 地址 | US22 |
| `eldercare.file.type` | `local` | 文件存储类型（local/cos） | US20 |
| `eldercare.notification.mock` | `true` | 通知 Mock 模式开关（true=写日志不真发，false=调用真实通道） | US13 |

### A.2 网关私有配置（service-gateway.yaml）

| 配置项 | 默认值 | 说明 | 关联 US |
|--------|--------|------|---------|
| `eldercare.security.cors.allowed-origins` | `http://localhost:5173,http://localhost:5174` | CORS 允许的源（多端域名） | US29 |
| `eldercare.security.cors.max-age` | `3600` | 预检缓存（秒） | US29 |
| `eldercare.gateway.sentinel.rules` | 见 US1 限流分级表 | Sentinel 限流规则（JSON 数组，Nacos 动态下发） | US1/US4 |
| `eldercare.gateway.rbac.role-mappings` | 见 US1 RBAC 表 | 路径-角色映射 | US1 |
| `eldercare.gateway.auth.whitelist` | `/auth/login,/auth/register,/actuator/health` | 无需鉴权的路径白名单（逗号分隔） | US1 |
| `spring.cloud.gateway.routes` | 8 条 HTTP + 2 条 WS | 路由规则 | US1 |

### A.3 Feign 单服务超时覆盖（按需）

| 配置项 | 说明 |
|--------|------|
| `spring.cloud.openfeign.client.config.service-ai.read-timeout` | AI 服务读取超时（可放大，但 > 15s 须登记例外） |

> 注：超出默认值 3 倍（read > 15s）的超时配置必须按 US10 FS-10.3 登记调用方、超时预算、重试条件和降级行为。

---

## 附录 B、Redis Key 命名规范

> 本附录统一全项目 Redis key 命名，避免各服务键名冲突和误删。所有 key 使用冒号 `:` 分层，统一前缀 `eldercare:`。

**命名格式：** `eldercare:{模块}:{业务域}:{标识}`

| Key 格式 | 类型 | TTL | 用途 | 关联 US |
|----------|------|-----|------|---------|
| `eldercare:auth:token:blacklist:{jti}` | String | Token 剩余有效期 | 登出 Token 黑名单 | US24 |
| `eldercare:auth:token:blacklist:user:{userId}` | String | 无（手动删除解封） | 封禁用户黑名单 | US24 |
| `eldercare:mq:consumed:{topic}:{eventId}` | String | 24h | MQ 消费幂等快速拦截标记（第一层） | US23 |
| `eldercare:gateway:ws:conn:{userId}` | Set | 无（连接关闭时清理） | WebSocket 连接数统计 | US1/US26 |
| `eldercare:lock:{业务域}:{标识}` | String | 看门狗自动续期 | Redisson 分布式锁 | US8 |
| `eldercare:cache:{业务域}:{标识}` | String/Hash | 业务自定义 | 通用缓存（CacheHelper） | US8 |

**约束：**
- 禁止使用无 TTL 的 key（黑名单 user 封禁 key 和 WS 连接 key 除外，二者有明确的生命周期管理）
- 禁止在 key 中存储敏感信息（Token 明文、密码、证件号）；黑名单 key 只存 JTI（JWT ID），不存 Token 本身
- MQ 幂等的最终正确性由 DB `mq_consumed` 表保障（见 US23），Redis key 仅作并发优化层，TTL 过期不影响正确性
- 各业务服务自定义 key 必须遵循 `eldercare:{service}:{...}` 前缀，不得占用 `auth`/`mq`/`gateway` 保留模块名
