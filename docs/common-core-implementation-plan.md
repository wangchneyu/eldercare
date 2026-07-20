# common-core 实现方案（待审核）

## 目标与范围

本阶段只开发 `common-core`：公共枚举（告警等级、任务状态、照护等级、角色类型、事件来源）与日期、ID、脱敏工具。不包含 Redis、分布式 Snowflake workerId、鉴权、事件发布和数据库映射。

现有 `BaseEntity`、`TraceContext` 保持不变。`R<T>` 只保留基于 `IErrorCode` 的失败工厂；异常体系增加 `RemoteCallException`；`BaseEvent` 增加独立的 `schemaVersion` 字段，以符合统一错误与 MQ 契约。已有 `DesensitizeUtil.mobilePhone` 保留并扩展。

## 建议代码结构

```text
com.eldercare.common.core
├── enums
│   ├── AlarmLevel.java
│   ├── TaskStatus.java
│   ├── CareLevel.java
│   ├── RoleType.java
│   ├── EventSource.java
│   └── DeviceType.java
└── utils
    ├── DateTimeUtil.java
    ├── IdUtil.java
    └── DesensitizeUtil.java
```

所有枚举统一暴露 `code`、`description` 与 `fromCode(String)`；持久化和接口传输使用稳定的英文 `code`，禁止使用 ordinal 或中文展示文案。

## 枚举草案

| 枚举 | 建议值（code） | 说明 |
|---|---|---|
| `AlarmLevel` | `INFO`、`WARNING`、`URGENT`、`CRITICAL` | 信息、预警、紧急、危急；严重程度递增。 |
| `TaskStatus` | `PENDING`、`IN_PROGRESS`、`COMPLETED`、`CANCELLED` | 待处理、处理中、已完成、已取消。 |
| `CareLevel` | `SELF_CARE`、`ASSISTED`、`DEPENDENT`、`DEMENTIA_CARE` | 自理、需协助、失能照护、失智照护。 |
| `RoleType` | `SUPER_ADMIN`、`ORG_ADMIN`、`CARE_MANAGER`、`CAREGIVER`、`DOCTOR`、`FAMILY_MEMBER`、`ELDERLY` | 平台管理员、机构管理员、照护主管、护理员、医生、家属、老人。 |
| EventSource | DEVICE、MANUAL、SCHEDULE、SYSTEM、THIRD_PARTY | 设备、人工、定时任务、内部系统、第三方。 |
| DeviceType | TV、SMART_WATCH、VITAL_SIGN_MONITOR、SOS_BUTTON、GATEWAY、CAMERA、OTHER | 智慧电视、智能手表、生命体征设备、紧急按钮、网关、摄像头、其他。 |

`OVERDUE` 不建议作为任务主状态入库，应通过 `deadline < now && status` 未完成动态计算；已逾期后完成的任务仍应为 `COMPLETED`。

## 设备身份与设备类型

`DeviceType` 只表达设备的物理/产品类别，不属于 `RoleType`。电视等设备使用独立的设备账号或设备凭证获取 Token，并通过绑定关系关联老人、房间或机构；不能使用老人账号登录。

建议同时在认证与审计模型中使用主体类别：`ActorType.USER`、`ActorType.DEVICE`、`ActorType.SYSTEM`、`ActorType.THIRD_PARTY`。其中 `ActorType` 可在后续 `common-security` 实现，本阶段仅定义 `DeviceType`。

设备端权限由设备授权策略控制，例如电视只能读取其绑定对象的展示内容、发起视频通话或紧急呼叫；不得继承老人或家属的完整权限。
## 工具类 API

### DateTimeUtil

仅使用 `java.time`，不新增 `Date` / `Calendar` API。

```java
public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
public static LocalDateTime now();
public static Instant nowUtc();
public static String format(LocalDateTime value);
public static String format(LocalDate value);
public static LocalDateTime parseDateTime(String value);
public static LocalDate parseDate(String value);
public static boolean isOverdue(LocalDateTime deadline, LocalDateTime now);
public static LocalDateTime toLocalDateTime(Instant value);
public static Instant toInstant(LocalDateTime value);
```

约定：数据库和事件使用 UTC `Instant` / `OffsetDateTime`；用户输入、展示和无时区时间转化默认使用 `Asia/Shanghai`；解析失败不返回 `null`。

### IdUtil

首版不引入外部依赖，仅提供 UUID：

```java
public static String uuid();
public static String uuid32();
public static String prefixed(String prefix);
```

不在 `common-core` 实现 Snowflake。它依赖多实例 workerId 治理，错误配置会产生碰撞；如后续需要短数字且时间有序的 ID，应在 `common-redis` 完成统一 workerId 分配后再实现。

### DesensitizeUtil

保留 `mobilePhone(String)`，增加：

```java
public static String name(String value);
public static String idCard(String value);
public static String email(String value);
public static String bankCard(String value);
public static String address(String value);
```

规则：`null` 原样返回；长度不足或格式无效的输入原样返回；日志不得输出脱敏前的值。

## 测试与验收

- 枚举：code 唯一、正常转换、未知 code。
- 日期：UTC/上海时区转换、闰年、跨日、解析失败与逾期边界。
- ID：批量生成无重复、前缀格式正确。
- 脱敏：null、空值、合法值、异常长度与格式。
- 公共响应只能输出已登记错误码；远程调用异常映射为 `100007`；事件基类必须包含独立 `schemaVersion`。

## 待确认的设计问题

1. `RoleType` 是否需要 `ELDERLY` 作为登录主体？是否还要平台管理员、护士、紧急响应人员等角色？
2. 任务是否有“已分配、已接单、暂停、复核中”等状态？这些应在首版固定，以避免已入库 code 变更。
3. 照护等级是否须遵循长护险或机构评估标准？若需，应提供正式等级与评估版本。`DEMENTIA_CARE` 与失能程度是两个维度，可能需要拆成两个字段。
4. 告警的 `URGENT` 与 `CRITICAL` 是否满足业务；是否还需独立的 `EMERGENCY`（立即呼救）？
5. 未知 code：建议枚举 `fromCode` 抛 `IllegalArgumentException`，由接口/消息转换层映射为参数错误，避免 core 与 Web 耦合。请确认。
6. 默认时区是否可固定为 `Asia/Shanghai`？如存在跨时区机构，机构时区应改为配置项。
7. UUID 是否可用于数据库主键、事件 ID 与对外单号？若对外单号要求短、可读或递增，需要单独设计。
8. 地址需保留到哪一层（市、区县、小区）？不同角色可见的身份证、银行卡掩码是否不同？
9. 首版设备类型是否包含智慧电视、手表、生命体征设备、SOS 按钮、网关、摄像头？是否还要区分门磁、烟感、跌倒雷达等具体设备？

## 实施顺序

审核确认后依次实现：枚举、日期工具、ID 工具、脱敏扩展、单元测试。上述 API 保持无 Spring 容器依赖，供所有微服务直接复用。
