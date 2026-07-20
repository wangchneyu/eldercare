---
name: eldercare-code-standards
description: 审查、设计和修改中铁和园智慧养老平台代码，使 Java/Spring 分层、REST 与 Feign 契约、RocketMQ 事件、错误码与异常、日志追踪、数据库和安全实现符合项目规范。用于代码评审、规范冲突检查、功能开发、重构、接口或消息契约变更、错误码登记以及质量门禁验证。
---

# Eldercare Code Standards

## Workflow

1. 确认任务是审查、设计还是修改；未获授权时只做只读检查。
2. 以 UTF-8 完整读取 [代码规范](references/code-standards.md)。涉及 REST 响应、错误码、异常、Feign 降级或 HTTP 状态时，同时以 UTF-8 完整读取 [错误码与异常规范](references/error-codes-and-exceptions.md)；Windows Shell 默认编码不是 UTF-8 时必须显式指定编码。
3. 检查仓库级 `AGENTS.md`、构建配置和相关模块，保留用户已有的未提交改动。
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

## Review Output

按严重程度排列可操作发现，并链接到具体文件和行号。每项说明冲突规则、当前证据、可能影响和建议修正。若没有发现，明确说明已检查范围和剩余测试风险。

## Keep References Synchronized

本 Skill 的 `references/` 是可移植基线。仓库内存在 `docs/代码规范文档.md` 或 `docs/错误码及异常设计.md` 时，将仓库文档视为项目登记源。

- 修改规范时，同时更新对应仓库文档和本 Skill 的参考副本。
- 两者不一致时，不要静默选择；先指出差异，并按用户确认或最新已审批的项目文档执行。
- 不要在 `SKILL.md` 重复完整规则；详细内容只维护在参考文件中。
