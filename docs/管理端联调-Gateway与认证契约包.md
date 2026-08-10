# 管理端联调 — Gateway / 认证契约包与全量路由表

> 依据《65-管理端真实接口联调需求文档》v3.0 准备 ｜ 负责人：王晨宇（Gateway + 基础架构）
> 用途：会议第 1 环节发言材料 + 前端接入书面契约

---

## 一、环境与版本

| 项 | 值 |
|---|---|
| Gateway 地址（测试） | `http://100.64.0.3:9999`（服务器）；本地 `http://localhost:9999` |
| 健康检查 | `GET /actuator/health`（网关及各服务） |
| 服务注册/配置中心 | Nacos（配置集中管理于 `service-gateway.yaml`） |
| 响应格式 | 所有响应均为 `R<T>`：`{ code, msg, data, traceId }` |
| 错误码 | 未认证/Token 失效：HTTP 401 + `code=110001`；权限不足：HTTP 403 + `code=110002` |
| 鉴权模式 | JWT Bearer；网关已开启 **RBAC 默认拒绝**（未配规则路径一律 403） |

---

## 二、`/api` 前缀结论（会议需拍板）

**结论：网关不感知 `/api` 前缀，由前端 Vite 代理剥离。**

```js
// vite.config.js 建议配置
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:9999',   // 联调时改为 http://100.64.0.3:9999
      changeOrigin: true,
      rewrite: (p) => p.replace(/^\/api/, '')
    }
  }
}
```

- 前端统一请求 `/api/...`，经代理去掉 `/api` 后进入网关
- 网关路由、RBAC 规则、白名单全部保持现状，零改动
- 生产环境由 Nginx 做同样的 rewrite，前端代码无需区分环境

---

## 三、全量路由表

> "剥离 /api 后"列为网关实际匹配的路径。

| 剥离 `/api` 后路径 | 目标服务 | 所需角色 | 白名单 | 对应页面 |
|---|---|---|---|---|
| `/auth/**` | service-operation | 免 Token（登录/注册/刷新） | ✅ | 登录 |
| `/admin/**` | service-operation | ADMIN, OPERATOR | | 管理后台、操作日志 |
| `/care/**` | service-care | ADMIN, CAREGIVER | | 长者档案（前端规范） |
| `/elderly/**` | service-care | ADMIN, CAREGIVER | | 长者档案（旧路由，并存） |
| `/iot/**` | service-iot | ADMIN, OPERATOR, CAREGIVER | | 设备台账（前端规范） |
| `/server/**` | service-iot | ADMIN, OPERATOR, CAREGIVER | | 设备台账（旧路由，并存） |
| `/family/**` | service-family | ADMIN, FAMILY | | 家属通知、园区生活 |
| `/vital/**` | service-vital | ADMIN, CAREGIVER, DOCTOR | | 体征数据 |
| `/ai/**` | service-ai | ADMIN, CAREGIVER | | AI 智能中心 |
| `/alert/**` | service-alert | ADMIN, CAREGIVER, OPERATOR | | 事件中心 |
| `/ws/alert/**`（WebSocket） | service-alert | 全部角色 | | 告警实时推送 |
| `/ws/family-living/**`（WebSocket） | service-family | ADMIN, FAMILY | | 家属生活推送 |

CORS：已放行 `http://localhost:5173`、`http://localhost:5174`（可新增）。

---

## 四、✅ URL 命名冲突（已按方案 A 落地）

前端已冻结的 URL 采用 `/api/{服务名}/v1/资源` 规范，与网关原有业务域前缀存在 2 处冲突，**已按方案 A 修复**：

| # | 前端已冻结 URL | 剥离 /api 后 | 网关处理 | 状态 |
|---|---|---|---|---|
| C1 | `GET /api/care/v1/elders` | `/care/v1/elders` | 新增 `/care/**` 路由 → service-care | ✅ 已就绪 |
| C2 | `GET /api/iot/devices` | `/iot/devices` | 新增 `/iot/**` 路由 → service-iot | ✅ 已就绪 |
| ✅ | `GET /api/admin/v1/audit-logs` | `/admin/v1/audit-logs` | `/admin/**` → service-operation | 匹配 |
| ✅ | `POST /api/auth/v1/admin/login` | `/auth/v1/admin/login` | 已加入默认白名单 | 匹配 |

**已完成的网关侧改动**：

- 新增 `route-care`（`/care/**`）、`route-iot`（`/iot/**`）两组路由，与旧路由并存
- 新增 RBAC 规则：`/care/**` → ADMIN, CAREGIVER；`/iot/**` → ADMIN, OPERATOR, CAREGIVER
- 默认白名单增加 v1 路径：`/auth/v1/admin/login`、`/auth/v1/register`、`/auth/v1/refresh`（登出不在白名单）
- 回归测试：common-security 58 个 + gateway 45 个全部通过

⚠️ **部署提醒**：服务器环境网关配置在 Nacos（`service-gateway.yaml`）集中管理，上线时需将上述路由、RBAC 规则同步到 Nacos 配置。

---

## 五、接口契约包 — 登录（按需求文档第 2 节模板）

```text
服务/页面：认证（service-operation）          负责人：徐亦豪（接口实现）/ 王晨宇（网关）
环境与版本：Gateway http://100.64.0.3:9999；service-operation 端口 8087
允许角色与数据范围：登录接口白名单免 Token；登录后按角色走 RBAC

接口：POST /auth/v1/admin/login（前端经 /api 前缀访问）
参数：
  loginAccount  String  必填  登录账号（对应 DB username）
  password      String  必填  明文密码（仅传输层，服务端 BCrypt 比对）

成功返回（HTTP 200）：
{
  "code": 0,
  "msg": "success",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "accountId": 1,
    "identityId": 1,
    "identityType": "ADMIN"
  },
  "traceId": "01J..."
}

字段说明：
  accessToken/refreshToken  JWT 令牌；请求头使用 Authorization: Bearer {accessToken}
  tokenType                 固定 "Bearer"
  expiresIn                 accessToken 有效期（秒），默认 7200
  accountId                 账号 ID（= 用户表主键 userId）
  identityId                身份 ID（一期 = userId；多身份体系后续扩展）
  identityType              身份类型，按主角色映射：
                            ADMIN→"ADMIN"；OPERATOR/CAREGIVER/DOCTOR→"STAFF"；FAMILY→"FAMILY"

空结果：不适用（登录无空态）
错误：
  401 / code=217001  用户名或密码错误（业务错误码以 operation 定义为准）
  401 / code=217002  账号已禁用（建议码值，实现时冻结）
  423 / code=217003  连续失败 5 次锁定 15 分钟（建议）
脱敏：响应不含密码哈希、不含角色明文列表以外的敏感信息
联调样例：
  ADMIN 账号：admin / （会前私发，不入文档）
  低权限账号：viewer / （会前私发）—— 用于验证 403
```

### 其他认证接口（一并给出）

| 接口 | 方法 | 网关鉴权 | 说明 |
|---|---|---|---|
| `/auth/v1/admin/login` | POST | 白名单 | 管理端登录 |
| `/auth/v1/refresh` | POST | 白名单 | refreshToken 换新 Token 对 |
| `/auth/v1/logout` | POST | 需 Token | 登出，jti 写入 Redis 黑名单即时失效 |

---

## 六、权限契约（页面/数据/字段三层）

| 层 | 机制 | 现状 |
|---|---|---|
| 页面（路径级） | 网关 RBAC `role-mappings`，default-deny 已开启 | ✅ 机制就绪，等"角色×页面"矩阵落地为规则 |
| 数据范围 | 各服务用 `SecurityContextHolder.getLoginUser().getUserId()` 过滤 | 各业务服务实现时负责 |
| 字段可见 | 各服务 DTO 按角色脱敏（电话/身份证/健康/密钥/Prompt 等） | 各业务服务实现时负责，网关不管 |

**角色对照（前端文档 → 系统枚举）：**

| 前端称呼 | 系统角色 | 备注 |
|---|---|---|
| ADMIN | `ADMIN` | |
| 运营 | `OPERATOR` | |
| 护理 | `CAREGIVER` | |
| 只读大屏 | ❌ 暂无 | **需拍板**：新增 `VIEWER` 角色 或 复用 `OPERATOR`（大屏账号只给只读页面规则） |

---

## 七、依赖与待决事项（会议要结果）

| # | 事项 | 责任人 | 截止 |
|---|---|---|---|
| 1 | `/api` 前缀方案（Vite 代理剥离）确认 | 前端（张翔） | 会上 |
| 2 | URL 冲突 C1/C2 —— 已按方案 A 落地，会上仅确认周知 | 网关（王晨宇） | 已完成 |
| 3 | "角色×页面"矩阵 + 数据范围 + 字段可见规则 | 产品（刘泳祺） | 会后 2 天 |
| 4 | 登录接口实现 + ADMIN/低权限种子账号 | 徐亦豪 | 会后 3 天 |
| 5 | 操作日志查询接口 `GET /admin/v1/audit-logs`（common-audit 采集已就绪，缺查询端） | 徐亦豪 | 会后 3 天 |
| 6 | 只读大屏角色：新增 `VIEWER` 还是复用 `OPERATOR` | 产品 + 网关 | 会上 |
| 7 | 缺失服务认领：指标聚合（驾驶舱）、scheduling、quality、rules/SOP、大屏配置 | 老师/产品 | 会上 |
| 8 | 分页契约统一（care 用 `page`，iot 用 `current/pages`，建议统一） | 全体后端 | 会上 |

---

## 八、明确禁止（与需求文档第 7 节一致，网关侧承诺）

- 一期仅保障 GET 联调；网关不为未审批的写操作开口子
- 网关不返回任何服务内部地址、密钥、Token 之外的凭据
- 样例账号密码私发，不进文档/群聊/截图
