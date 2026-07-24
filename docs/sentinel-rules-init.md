# Sentinel 网关流控规则初始化

> Gateway 已内置硬编码兜底规则，以下为 Nacos 动态规则配置指引，用于运行时调整 QPS 阈值。

## 在 Nacos 控制台创建配置

1. 打开 Nacos 控制台：http://localhost:8848/nacos（默认账号 nacos/nacos）
2. 进入 **配置管理 → 配置列表**
3. 点击右上角 **+** 创建配置
4. 填写以下信息：

| 字段 | 值 |
|------|-----|
| Data ID | `gateway-sentinel-flow-rules.json` |
| Group | `SENTINEL_GROUP` |
| 配置格式 | JSON |

5. 配置内容粘贴以下 JSON：

```json
[
  { "resource": "route-auth", "count": 20.0, "intervalSec": 1 },
  { "resource": "route-alert", "count": 500.0, "intervalSec": 1 },
  { "resource": "route-admin", "count": 200.0, "intervalSec": 1 },
  { "resource": "route-server", "count": 200.0, "intervalSec": 1 },
  { "resource": "route-elderly", "count": 200.0, "intervalSec": 1 },
  { "resource": "route-family", "count": 200.0, "intervalSec": 1 },
  { "resource": "route-vital", "count": 200.0, "intervalSec": 1 },
  { "resource": "route-ai", "count": 200.0, "intervalSec": 1 }
]
```

6. 点击 **发布**

## 规则说明

| 路由 | QPS 上限 | 设计依据 |
|------|---------|---------|
| route-auth | 20 | 防暴力破解 |
| route-alert | 500 | P0 生命安全主链路，高吞吐 |
| route-admin | 200 | 通用业务 |
| route-server | 200 | 通用业务 |
| route-elderly | 200 | 通用业务 |
| route-family | 200 | 通用业务 |
| route-vital | 200 | 通用业务 |
| route-ai | 200 | 通用业务 |

## 热更新

修改 Nacos 中的 JSON 后，规则在 ~30s 内自动推送到 Gateway 生效，无需重启。

## 兜底机制

即使 Nacos 不可用或配置不存在，Gateway 启动时会加载硬编码兜底规则（与上述 JSON 相同的阈值），确保限流始终生效。
