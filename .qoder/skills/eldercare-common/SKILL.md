---
name: eldercare-common
description: Guide developers to correctly use eldercare-platform common components (common-core, common-security, common-redis, common-feign, common-file, common-notify, common-audit, common-swagger). Use when writing new service code, creating controllers, adding authentication, using Redis, making Feign calls, uploading files, adding audit logs, or sending notifications in this project.
---

# Eldercare Common Components

## Dependency Introduction

All versions managed by parent POM. **Never write `<version>` in child modules.**

```xml
<!-- Required for all services -->
<dependency>
    <groupId>com.eldercare</groupId>
    <artifactId>common-core</artifactId>
    <version>${project.version}</version>
</dependency>
<!-- Add per need: common-security, common-redis, common-feign, common-file, common-audit, common-swagger, common-notify -->
```

## Unified Response `R<T>`

```java
return R.ok();                          // success, no data
return R.ok(dto);                       // success, with data
return R.fail(SystemErrorCode.BAD_REQUEST);  // fail with error code
return R.fail(120001, "自定义消息");      // fail with custom msg
```

JSON format: `{"code":0,"msg":"success","data":{},"traceId":"..."}`

## Business Exception

```java
throw new BizException(SystemErrorCode.BAD_REQUEST);
throw new BizException(CareErrorCode.CARE_PLAN_NOT_FOUND);
```

Global handler auto-converts to `R.fail(...)`. No manual try-catch needed.

### Custom Error Code (per service)

```java
@Getter @AllArgsConstructor
public enum CareErrorCode implements IErrorCode {
    CARE_PLAN_NOT_FOUND(140001, "照护计划不存在", HttpStatus.NOT_FOUND);
    private final int code;
    private final String msg;
    private final HttpStatus httpStatus;
}
```

Error code ranges: 100000-109999 system, 110000-119999 auth, 120000+ per service.

## Utility Classes

```java
IdUtil.nextId();                    // Snowflake Long ID
IdUtil.uuid32();                    // 32-char UUID
DateTimeUtil.now();                 // LocalDateTime (Asia/Shanghai)
DateTimeUtil.format(dt);            // "yyyy-MM-dd HH:mm:ss"
DesensitizeUtil.mobilePhone(phone); // "138****5678"
```

## Entity Base Class

```java
@Data @EqualsAndHashCode(callSuper = true) @TableName("t_xxx")
public class Xxx extends BaseEntity {
    // id, createTime, updateTime, createBy, updateBy inherited
    private String name;
}
```

## Security (common-security)

### Get Current User

```java
Long userId = SecurityContextHolder.getCurrentUserId();
LoginUser user = SecurityContextHolder.getLoginUser();
```

### Role Restriction

```java
@RequireRole(UserRole.ADMIN)
@DeleteMapping("/user/{id}")
public R<Void> deleteUser(@PathVariable Long id) { ... }

@RequireRole({UserRole.ADMIN, UserRole.CAREGIVER})
public R<Void> manage(...) { ... }
```

### JWT (login endpoint)

```java
JwtTokenPair pair = jwtTokenProvider.createTokenPair(loginUser);
```

### Config

```yaml
eldercare:
  security:
    secret: ${ELDERCARE_JWT_SECRET}   # min 32 chars
    whitelist:
      - /auth/login
      - /swagger-ui/**
```

Disable for dev: `eldercare.security.enabled: false`

## Redis (common-redis)

### Config

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

### Cache Operations

```java
redisService.set("user:1001", dto, 3600);  // TTL seconds
UserDTO u = redisService.get("user:1001");
redisService.delete("user:1001");
redisService.increment("counter:key");
```

### Distributed Lock

```java
String requestId = IdUtil.uuid32();
try {
    if (!redisLock.tryLock("lock:order:123", requestId, 30, 5)) {
        throw new BizException(SystemErrorCode.TOO_MANY_REQUESTS);
    }
    // business logic
} finally {
    redisLock.unlock("lock:order:123", requestId);
}
```

Key naming: `{domain}:{entity}:{id}` e.g. `user:info:1001`, `lock:order:4001`

## Feign (common-feign)

### Startup Annotation

```java
@EnableFeignClients(basePackages = "com.eldercare.common.feign.client")
```

### Call

```java
@Autowired private AlertClient alertClient;
AlertRemoteDTO alert = alertClient.getAlert(alertId);
```

- Auto-propagates X-User-Id, X-User-Roles, X-Trace-Id
- Timeout: connect 5s / read 10s, NO retry (Sentinel controls)
- Failure throws `RemoteCallException` → 502 response

## MQ (RocketMQ in common-core)

### Send

```java
event.fillEnvelope(MqTopicConstants.IOT_DEVICE_DATA, "service-iot:1.0.0");
event.setPayload(payload);
mqProducer.send(MqTopicConstants.IOT_DEVICE_DATA, event);
```

### Consume

```java
@Component
@RocketMQMessageListener(topic = "...", consumerGroup = "service-xxx-consumer")
public class XxxConsumer extends BaseConsumer<XxxEvent>
        implements RocketMQListener<MessageExt> {
    @Override public void onMessage(MessageExt msg) { super.onMessage(msg); }
    @Override protected void processEvent(XxxEvent event) { /* logic */ }
    @Override protected Class<XxxEvent> getEventClass() { return XxxEvent.class; }
}
```

## File Storage (common-file)

```java
String fileId = fileStorageService.upload(inputStream, filename, contentType);
String url = fileStorageService.getPreviewUrl(fileId, 30);
```

Config: `eldercare.file.type: local | cos`

## Audit Log (common-audit)

```java
@AuditLog("删除老人信息")
@DeleteMapping("/elderly/{id}")
public R<Void> delete(@PathVariable Long id) { ... }

@AuditLog(value = "导出", saveParams = false, saveResult = false)
```

## Swagger (common-swagger)

Zero-config. Access: `http://localhost:{port}{context-path}/swagger-ui.html`

```java
@Tag(name = "照护计划")
@Operation(summary = "查询详情")
```

## Notify (common-notify)

```java
notifyService.sendSms(SmsRequest.of("138...", "SMS_001", Map.of("code", "123456")));
```

Config: `eldercare.notify.type: mock | aliyun`
