# common-feign 跨服务调用契约设计与开发说明文档

## 一、模块定位与设计规范

`common-feign` 模块用于集中声明所有微服务之间的跨服务 REST API 客户端及传输契约对象。它是微服务之间进行同步通信的统一契约声明模块。

为了保证微服务架构的高内聚与低耦合，必须严格遵守以下设计规范：

1. **命名规范**：
   - 跨服务调用的数据传输对象统一命名为 **`XxxRemoteDTO`**（必须以 `RemoteDTO` 结尾），例如 `UserRemoteDTO`。
   - 严禁在 Feign 接口和 Feign DTO 中混用业务服务内部的 `Entity` 或 `VO`。

2. **接口传输约束**：
   - 根据《错误码及异常设计》，**Feign 接口不使用 REST 的 `R<T>` 统一包装对象，直接返回原始数据类型**（如 `UserRemoteDTO` 或 `Boolean`）。
   - 服务端 Controller 独立声明，**绝对不要继承或实现 Feign Client 接口**，以确保服务提供方内部代码重构时不会直接对消费方造成破坏性编译阻断。

3. **异常处理与熔断降级**：
   - Feign 客户端的熔断处理类必须实现 `FallbackFactory<T>`，在远程调用异常（如超时、熔断、5xx 错误）时，直接**记录原始异常堆栈并抛出 `BizException(SystemErrorCode.REMOTE_CALL_FAILED, cause)`**。
   - 消费方服务通过全局异常处理器（`GlobalExceptionHandler`）拦截该异常，并按标准转为 `100007` 响应体返回给客户端。

---

## 二、代码包与模块结构

```text
com.eldercare.common.feign
├── client
│   ├── AuthClient.java        (调用：service-auth 认证授权)
│   ├── IotClient.java         (调用：service-iot 设备遥测)
│   ├── AlertClient.java       (调用：service-alert 预警中心)
│   ├── CareClient.java        (调用：service-care 照护服务)
│   ├── VitalClient.java       (调用：service-vital 生命体征)
│   └── FamilyClient.java      (调用：service-family 家属绑定)
├── fallback
│   ├── AuthFallbackFactory.java
│   ├── IotFallbackFactory.java
│   ├── AlertFallbackFactory.java
│   ├── CareFallbackFactory.java
│   ├── VitalFallbackFactory.java
│   └── FamilyFallbackFactory.java
└── dto
    ├── auth
    │   └── UserRemoteDTO.java
    ├── iot
    │   └── DeviceTelemetryRemoteDTO.java
    ├── alert
    │   └── AlertRemoteDTO.java
    ├── care
    │   └── CarePlanRemoteDTO.java
    ├── vital
    │   └── VitalRecordRemoteDTO.java
    └── family
        └── FamilyMemberRemoteDTO.java
```

---

## 三、契约结构设计明细

### 3.1 DTO 骨架声明 (RemoteDTO)

目前已在 [dto](file:///e:/tmp/eldercare-platform/common/common-feign/src/main/java/com/eldercare/common/feign/dto) 包下定义了各个微服务调用的最核心字段，各微服务开发人员可以在后续业务开发中**向前兼容地追加**字段：

- **`UserRemoteDTO`** (服务提供方：`service-auth`)
  - 核心属性：`id` (用户ID), `username` (账号), `realName` (真实姓名), `roles` (角色列表)。
- **`DeviceTelemetryRemoteDTO`** (服务提供方：`service-iot`)
  - 核心属性：`deviceId` (设备ID), `deviceType` (设备类型), `properties` (遥测K-V键值对), `reportTime` (上报时间)。
- **`AlertRemoteDTO`** (服务提供方：`service-alert`)
  - 核心属性：`id` (告警ID), `elderId` (长者ID), `alertLevel` (告警级别), `content` (告警详情), `status` (状态), `triggerTime` (触发时间)。
- **`CarePlanRemoteDTO`** (服务提供方：`service-care`)
  - 核心属性：`id` (计划ID), `elderId` (长者ID), `caregiverId` (护工ID), `status` (计划状态), `startDate`/`endDate` (生命周期限制)。
- **`VitalRecordRemoteDTO`** (服务提供方：`service-vital`)
  - 核心属性：`id` (体征ID), `elderId` (长者ID), `vitalType` (指标类型), `value` (指标数值), `recordTime` (记录时间)。
- **`FamilyMemberRemoteDTO`** (服务提供方：`service-family`)
  - 核心属性：`id` (绑定ID), `elderId` (长者ID), `name` (家属姓名), `relationship` (关系), `phone` (联系电话)。

---

## 四、客户端使用方法示例

各端业务开发人员如需在自己的微服务（如 `service-care`）中调用其他服务的接口，请按照以下步骤进行：

### 4.1 引入 Maven 坐标
在消费方服务（如 `service-care/pom.xml`）中引入 `common-feign` 依赖：
```xml
<dependency>
    <groupId>com.eldercare</groupId>
    <artifactId>common-feign</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 4.2 开启 Feign 扫描
在消费方微服务的 Spring Boot 启动类上打上 `@EnableFeignClients` 注解，**必须明确配置扫描包路径**，否则无法扫描到 `common-feign` 包中的客户端类：
```java
package com.eldercare.care;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.eldercare.common.feign.client") // 👈 必须指定扫描基础包
public class ServiceCareApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceCareApplication.class, args);
    }
}
```

### 4.3 注入并进行同步调用
在具体的 Service 或业务逻辑中直接 `@Autowired` 或构造器注入所需要调用的客户端：
```java
package com.eldercare.care.service.impl;

import com.eldercare.common.feign.client.AuthClient;
import com.eldercare.common.feign.dto.UserRemoteDTO;
import org.springframework.stereotype.Service;

@Service
public class CareServiceImpl implements ICareService {

    private final AuthClient authClient;

    public CareServiceImpl(AuthClient authClient) {
        this.authClient = authClient;
    }

    @Override
    public void assignCareTask(String username) {
        // 直接调用 Feign 客户端获取用户数据，抛出的熔断异常会被全局处理器自动捕获
        UserRemoteDTO remoteUser = authClient.getUserInfo(username);
        
        Long userId = remoteUser.getId();
        // 接下来执行具体的派单分配逻辑...
    }
}
```

---

## 五、契约修改规范

1. **追加字段**：各端开发人员若需要新的数据字段，可直接修改 [dto](file:///e:/tmp/eldercare-platform/common/common-feign/src/main/java/com/eldercare/common/feign/dto) 下的 `RemoteDTO` 类。追加字段**不能添加强制校验注解（如 `@NotNull`）**，否则会造成旧版本服务调用时解析参数校验报错。
2. **新增 API**：在对应的 `XxxClient` 下新增 mapping 路径，同时在对应的 `XxxFallbackFactory` 里**同步实现空重写并抛出远程调用失败异常**。
3. **安全审计规范**：根据《代码规范文档》，Feign 调用严禁传递或打印密码、Token 等敏感字眼，打印调试日志时，应对核心敏感参数做手动脱敏。
