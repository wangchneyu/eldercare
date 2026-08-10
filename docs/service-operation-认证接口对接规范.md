# service-operation 认证接口对接规范

> 本文档面向 service-operation 业务开发者，说明如何实现认证接口以走通 Gateway 鉴权流程。

## 一、整体流程

```
前端 POST /auth/login
    → Gateway（白名单放行，不鉴权）
        → service-operation（验证密码、签发 Token）
            → 返回 accessToken + refreshToken

前端 GET /admin/users（带 Token）
    → Gateway（验签 → 查黑名单 → RBAC 角色校验）
        → service-operation（处理业务）
```

## 二、必须提供的接口

| 接口 | 方法 | 网关是否鉴权 | 说明 |
|------|------|:---:|------|
| `/auth/v1/admin/login` | POST | 否（白名单） | **管理端登录（前端契约已冻结，见第十四节）** |
| `/auth/v1/register` | POST | 否（白名单） | 注册（v1） |
| `/auth/v1/refresh` | POST | 否（白名单） | 用 refreshToken 换新 accessToken（v1） |
| `/auth/v1/logout` | POST | **是** | 登出（v1，需携带 Token） |
| `/auth/login` 等无 v1 路径 | POST | 同上 | 旧版兼容，网关白名单同时保留 |

## 三、Maven 依赖

```xml
<dependency>
    <groupId>com.eldercare</groupId>
    <artifactId>common-security</artifactId>
    <version>${project.version}</version>
</dependency>
```

引入后自动获得：
- `JwtTokenProvider` — Token 签发/验签/刷新
- `JwtAuthenticationFilter` — 从网关透传头提取用户身份
- `SecurityContextHolder` — 获取当前登录用户
- `@RequireRole` — 方法级角色校验注解

## 四、配置（必须与 Gateway 一致）

```yaml
eldercare:
  security:
    enabled: true
    secret: ${ELDERCARE_JWT_SECRET:ElderCarePlatform2024SecretKey!@#$%^}
    access-token-expiration: 7200      # 秒（2小时）
    refresh-token-expiration: 604800   # 秒（7天）
```

> **关键**：`secret` 必须和 Gateway 完全一致，否则网关验签失败返回 401。

## 五、登录接口实现

### 5.1 Service 层

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 登录 — 验证密码后签发 Token 对
     */
    public JwtTokenPair login(String username, String password) {
        UserEntity user = userMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BizException(OperationErrorCode.LOGIN_FAILED);
        }

        // 构建 LoginUser — 网关就是解析这三个字段
        LoginUser loginUser = new LoginUser(
            user.getId(),                              // userId (Long)
            user.getUsername(),                        // username (String)
            parseRoles(user.getRole())                 // roles (Set<UserRole>)
        );

        return jwtTokenProvider.createTokenPair(loginUser);
    }

    /**
     * 刷新 Token
     */
    public JwtTokenPair refresh(String refreshToken) {
        JwtTokenPair pair = jwtTokenProvider.refreshAccessToken(refreshToken);
        if (pair == null) {
            throw new BizException(OperationErrorCode.TOKEN_INVALID);
        }
        return pair;
    }

    /**
     * 登出 — 将 Token 加入 Redis 黑名单
     */
    public void logout(String token) {
        Claims claims = jwtTokenProvider.getClaims(token);
        String jti = claims.getId();
        long ttl = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (ttl > 0) {
            redisTemplate.opsForValue().set(
                "eldercare:auth:token:blacklist:" + jti,
                "1",
                ttl,
                TimeUnit.MILLISECONDS
            );
        }
    }

    private Set<UserRole> parseRoles(String roleStr) {
        return Arrays.stream(roleStr.split(","))
            .map(String::trim)
            .map(UserRole::valueOf)
            .collect(Collectors.toSet());
    }
}
```

### 5.2 Controller 层

```java
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<JwtTokenPair> login(@RequestBody @Valid LoginRequest request) {
        return R.ok(authService.login(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/refresh")
    public R<JwtTokenPair> refresh(@RequestBody @Valid RefreshRequest request) {
        return R.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        authService.logout(token);
        return R.ok();
    }
}
```

### 5.3 请求/响应对象

```java
@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}

@Data
public class RefreshRequest {
    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
```

## 六、JWT Token 内部结构

`JwtTokenProvider` 自动写入以下 Claims，开发者无需手动构造：

```json
{
  "jti": "a1b2c3d4e5f6...",
  "sub": "admin",
  "userId": 1,
  "username": "admin",
  "roles": "ADMIN,OPERATOR",
  "tokenType": "access",
  "iat": 1721500000,
  "exp": 1721507200
}
```

| Claim | 类型 | 说明 |
|-------|------|------|
| `jti` | String | Token 唯一标识，登出时作为黑名单 key |
| `sub` | String | 用户名（JWT 标准字段） |
| `userId` | Long | 用户ID，网关透传为 `X-User-Id` |
| `username` | String | 用户名，网关透传为 `X-Username` |
| `roles` | String | 逗号分隔角色，网关透传为 `X-User-Roles` |
| `tokenType` | String | `access` 或 `refresh` |

## 七、Redis 黑名单 Key 规范

| Key 格式 | 用途 | TTL |
|----------|------|-----|
| `eldercare:auth:token:blacklist:{jti}` | 单个 Token 吊销（登出） | Token 剩余有效期 |
| `eldercare:auth:token:blacklist:user:{userId}` | 封禁用户全部 Token | 无（手动删除解封） |

## 八、可用角色枚举

```java
public enum UserRole {
    ADMIN,      // 系统管理员
    FAMILY,     // 家属
    CAREGIVER,  // 看护人员
    OPERATOR,   // 运营人员
    DOCTOR      // 医生
}
```

## 九、网关 RBAC 路径-角色映射（已配置）

> ⚠️ **重要变更**：网关已开启 RBAC 默认拒绝模式（`eldercare.security.rbac.default-deny: true`）。
> 未配置角色规则的非白名单路径一律返回 403（原来默认放行）。
> **新增任何接口路径前，必须先联系网关负责人同步配置 role-mappings 规则，否则接口无法访问。**

| 路径 | 允许角色 |
|------|----------|
| `/admin/**` | ADMIN, OPERATOR |
| `/server/**` | ADMIN, OPERATOR, CAREGIVER |
| `/elderly/**` | ADMIN, CAREGIVER |
| `/family/**` | ADMIN, FAMILY |
| `/vital/**` | ADMIN, CAREGIVER, DOCTOR |
| `/ai/**` | ADMIN, CAREGIVER |
| `/alert/**` | ADMIN, CAREGIVER, OPERATOR |
| `/ws/alert/**` | ADMIN, CAREGIVER, OPERATOR, FAMILY |
| `/ws/family-living/**` | ADMIN, FAMILY |

## 十、前端调用示例

```javascript
// 1. 登录
const { data } = await axios.post('/auth/login', {
  username: 'admin',
  password: '123456'
})
// data = { accessToken, refreshToken, expiresIn }

// 2. 设置请求头
axios.defaults.headers.common['Authorization'] = `Bearer ${data.accessToken}`

// 3. 访问受保护接口
const users = await axios.get('/admin/users')

// 4. Token 过期后刷新
const newPair = await axios.post('/auth/refresh', {
  refreshToken: data.refreshToken
})

// 5. 登出
await axios.post('/auth/logout')
```

## 十一、统一响应格式

所有接口返回 `R<T>`：

```json
{
  "code": 0,
  "msg": "success",
  "data": { ... },
  "traceId": "01J..."
}
```

错误时：
```json
{
  "code": 217001,
  "msg": "用户名或密码错误",
  "data": null,
  "traceId": "01J..."
}
```

## 十二、安全加固要求（⚠️ 必读）

### 12.1 注册接口（`/auth/register`）安全红线 🔴

- **角色必须服务端硬编码为 `FAMILY`**，请求体中即使携带 `roles` 字段也必须忽略或报错——否则攻击者可注册出 ADMIN 账号
- 员工角色（`ADMIN / CAREGIVER / OPERATOR / DOCTOR`）**禁止通过注册接口创建**，只能走管理员后台
- 密码 BCrypt 加密存储，长度 ≥ 8 位，建议含字母+数字校验
- 用户名唯一性校验，防重复注册

### 12.2 登录接口（`/auth/login`）要求 🔴

- JWT 中的 `roles` claim **必须从数据库查出的角色生成**，绝不采信客户端传入的角色
- 校验账号状态：禁用（`status=0`）账号登录直接拒绝
- 连续登录失败 5 次锁定 15 分钟（用 Redis 计数：`eldercare:auth:login:fail:{username}`）
- `secret` 与网关保持一致（见第四节）

### 12.3 用户表建议结构

```sql
sys_user (
  id            BIGINT PRIMARY KEY,
  username      VARCHAR(64) UNIQUE NOT NULL,
  password      VARCHAR(100) NOT NULL,   -- BCrypt 哈希
  roles         VARCHAR(128) NOT NULL,   -- 逗号分隔，如 "FAMILY"
  status        SMALLINT DEFAULT 1,      -- 1启用 0禁用
  created_by    BIGINT,                  -- 员工账号记录创建者（审计）
  create_time   TIMESTAMP
)
```

### 12.4 员工账号管理（`/admin/users/**`）

- 创建/禁用用户接口加 `@RequireRole(UserRole.ADMIN)`（网关 RBAC + 方法级双重校验）
- 创建时校验目标角色合法性，ADMIN 角色建议仅允许已有 ADMIN 创建

## 十三、RBAC 角色鉴权需要 service-operation 提供什么

网关 RBAC **只校验 JWT 里的角色，从不查数据库**——角色信息的正确性完全由 service-operation 保证。具体契约如下：

### 13.1 提供权威的角色数据源 🔴

- 数据库 `sys_user.roles` 是角色的**唯一权威来源**
- 角色值必须严格为 `UserRole` 枚举名之一：`ADMIN / FAMILY / CAREGIVER / OPERATOR / DOCTOR`（区分大小写）
- ⚠️ 网关解析 Token 时使用 `UserRole.valueOf()`：**非法角色字符串会导致用户解析失败，网关直接返回 401**。不要自造角色名（如 "SUPERUSER"）；新增角色需先联系网关负责人同步修改 `UserRole` 枚举和 RBAC 规则

### 13.2 登录时将角色写入 JWT 🔴

- 登录流程：从 DB 查角色 → 构建 `LoginUser(userId, username, roles)` → `createTokenPair()`
- `roles` claim 是逗号分隔字符串（如 `"ADMIN,OPERATOR"`），由 `JwtTokenProvider` 自动生成，无需手动拼接
- 绝不采信客户端传入的角色（见 12.2）

### 13.3 角色变更 / 账号禁用时及时吊销 🔴

- JWT 是无状态的：角色在签发时快照进 Token，**到期前不会自动更新**
- 修改用户角色、禁用账号后，必须写用户级黑名单 `eldercare:auth:token:blacklist:user:{userId}`（无 TTL），网关会立即使该用户全部 Token 失效（含 refreshToken 签发的旧会话）
- 解封时删除该 key 即可
- 网关已实现对该 key 的校验（`TokenBlacklistFilter`），命中返回 401

### 13.4 不需要 service-operation 提供的

| 职责 | 归属 | 说明 |
|------|------|------|
| 路径→角色映射规则 | 网关（`role-mappings` 配置） | 你只管“用户有什么角色”，不管“什么路径要什么角色” |
| Token 验签 / 过期判断 | 网关（`JwtAuthGlobalFilter`） | 基于共享 secret，无需你参与 |
| 数据级权限 | 你自己的业务代码 | 如“家属只能看自己绑定的长者”，网关管不了，需用 `SecurityContextHolder` 里的 userId 过滤 |

## 十四、管理端登录联调契约（前端已冻结，⚠️ 不能改）

### 14.1 接口定义

- **路径**：`POST /auth/v1/admin/login`（前端经 `/api` 前缀访问，由代理剥离后到达网关）
- **请求体**（仅两个字段，不要多余字段）：

```json
{ "loginAccount": "admin", "password": "xxx" }
```

- **响应**（`R<LoginResponse>`，字段缺一不可）：

```json
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
```

### 14.2 字段映射规则（一期轻量方案）

| 字段 | 取值规则 |
|------|------|
| `tokenType` | 固定 `"Bearer"` |
| `expiresIn` | accessToken 有效期（秒），与 `access-token-expiration` 一致 |
| `accountId` | = 用户表主键 userId |
| `identityId` | = userId（一期；多身份体系后续扩展） |
| `identityType` | 按主角色映射，见下表 |

| 用户角色 | identityType |
|---------|--------------|
| ADMIN | `ADMIN` |
| OPERATOR / CAREGIVER / DOCTOR | `STAFF` |
| FAMILY | `FAMILY` |

### 14.3 错误形态（前端验收依据）

| 场景 | HTTP | 说明 |
|------|------|------|
| 用户名/密码错误 | 401 | 业务错误码由 operation 定义（建议 217xxx 段） |
| 账号已禁用 | 401 | 同上，msg 区分 |
| 连续失败锁定 | 423 | 5 次失败锁 15 分钟（见 12.2） |
| Token 无效/过期访问受保护接口 | 401 | code=110001（网关返回，无需实现） |
| 低权限访问高权限路径 | 403 | code=110002（网关返回，无需实现） |

### 14.4 联调种子账号（必须提供）

| 账号 | 角色 | 用途 |
|------|------|------|
| `admin` | ADMIN | 前端验证正常登录与管理端全量页面 |
| `viewer`（低权限） | FAMILY 或只读角色 | 前端验证 403/`110002` |

- 密码使用 BCrypt 初始化脚本写入数据库，与联调环境同步
- **账号密码私聊交付，禁止出现在文档/群聊/截图中**

## 十五、对接检查清单

- [ ] pom.xml 引入 `common-security`
- [ ] 配置 `eldercare.security.secret` 与 Gateway 一致
- [ ] 实现 `POST /auth/login`，使用 `JwtTokenProvider.createTokenPair()`
- [ ] 实现 `POST /auth/refresh`，使用 `JwtTokenProvider.refreshAccessToken()`
- [ ] 实现 `POST /auth/logout`，写 Redis 黑名单
- [ ] 用户表包含 `role` 字段，值为 `UserRole` 枚举名
- [ ] 密码使用 BCrypt 加密存储
- [ ] **注册接口 roles 硬编码 FAMILY，忽略请求体角色**（见 12.1）
- [ ] **登录角色从 DB 加载，禁用账号拒绝登录**（见 12.2）
- [ ] **登录失败锁定机制（Redis 计数）**（见 12.2）
- [ ] **员工账号仅管理员可创建，带 `created_by` 审计字段**（见 12.4）
- [ ] **新接口路径已同步网关配置 RBAC 规则（默认拒绝模式，否则 403）**
- [ ] **数据库角色值与 `UserRole` 枚举名严格一致（非法值会导致网关 401）**（见 13.1）
- [ ] **角色变更/账号禁用时写用户级黑名单吊销全部 Token**（见 13.3）
- [ ] **管理端登录实现 v1 契约：`/auth/v1/admin/login` + `loginAccount/password` 入参 + 6 字段返回体**（见 14.1）
- [ ] **identityType 按角色映射规则返回**（见 14.2）
- [ ] **提供 ADMIN（admin）+ 低权限（viewer）两个种子账号**（见 14.4）
- [ ] 本地启动验证：Gateway + service-operation 同时运行，登录获取 Token 后访问受保护接口
