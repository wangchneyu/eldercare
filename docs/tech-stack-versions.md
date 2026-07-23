# 中铁和园智慧养老平台 — 统一技术栈版本清单

---

## 一、总览表

| 技术 | 版本 | Maven 坐标 | 备注 |
|------|------|-----------|------|
| Java | 17（LTS） | — | 最低要求，推荐 17.0.11+ |
| Spring Boot | 3.2.4 | `org.springframework.boot:spring-boot-starter-parent:3.2.4` | 父 POM 继承 |
| Spring Cloud | 2023.0.1 | `org.springframework.cloud:spring-cloud-dependencies:2023.0.1` | BOM 导入 |
| Spring Cloud Alibaba | 2023.0.1.0 | `com.alibaba.cloud:spring-cloud-alibaba-dependencies:2023.0.1.0` | BOM 导入 |
| Spring Cloud Gateway | 跟随 Spring Cloud | `org.springframework.cloud:spring-cloud-starter-gateway` | 仅 gateway-service 用 |
| OpenFeign | 跟随 Spring Cloud | `org.springframework.cloud:spring-cloud-starter-openfeign` | 版本由 BOM 管理 |
| Sentinel | 跟随 SCA | `com.alibaba.cloud:spring-cloud-starter-alibaba-sentinel` | 版本由 BOM 管理 |
| Nacos Client | 跟随 SCA（服务端 3.2.3） | `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery` | 客户端版本由 BOM 锁定 |
| RocketMQ Starter | 2.2.3 | `org.apache.rocketmq:rocketmq-spring-boot-starter:2.2.3` | 兼容 Spring Boot 3.2.x |
| MyBatis-Plus | 3.5.7 | `com.baomidou:mybatis-plus-spring-boot3-starter:3.5.7` | 注意是 boot3 变体 |
| PostgreSQL JDBC | 42.6.2 | `org.postgresql:postgresql` | 版本由 Boot 父 POM 管理 |
| MySQL Connector | 8.3.0 | `com.mysql:mysql-connector-j` | 仅需要时用，版本由 Boot 管理 |
| Spring Data Redis | 跟随 Boot | `org.springframework.boot:spring-boot-starter-data-redis` | Lettuce 客户端 |
| Nginx | 1.26.3（stable） | — | 反向代理/静态资源/SSL 终结 |

---

## 二、分类说明

### 2.1 核心框架

| 项 | 说明 |
|----|------|
| Java 17 | Spring Boot 3.x 最低要求。使用 Records、Sealed Classes、Text Blocks 等特性 |
| Spring Boot 3.2.4 | 基于 Spring Framework 6.1.x，Jakarta EE 10（`jakarta.*` 包名） |
| Jakarta EE | **不再使用** `javax.servlet`/`javax.validation`，全部改为 `jakarta.*` |

### 2.2 微服务治理

| 项 | 说明 |
|----|------|
| Spring Cloud 2023.0.1 | 包含 Gateway、OpenFeign、LoadBalancer、CircuitBreaker |
| Spring Cloud Alibaba 2023.0.1.0 | 包含 Nacos Discovery/Config、Sentinel、Seata |
| Nacos 服务端 | 3.2.3 版本，客户端由 SCA BOM 自动匹配 |
| Sentinel | 流控/熔断，版本跟随 SCA，不单独指定 |
| OpenFeign | 声明式 HTTP 客户端，版本跟随 Spring Cloud |

### 2.3 数据层

| 项 | 说明 |
|----|------|
| MyBatis-Plus 3.5.7 | **必须用 `mybatis-plus-spring-boot3-starter`**，不是 `mybatis-plus-boot-starter` |
| PostgreSQL | 主数据库，JDBC 驱动版本由 Boot 父 POM 管理（42.6.x） |
| MySQL | 备选，仅特殊场景使用，驱动由 Boot 管理 |
| Redis | Spring Data Redis + Lettuce，版本跟随 Boot |

### 2.4 中间件

| 项 | 说明 |
|----|------|
| RocketMQ 2.2.3 | `rocketmq-spring-boot-starter`，支持 Spring Boot 3.2.x + Jakarta |
| Nginx 1.26.3 | 生产环境反向代理、SSL 终结、静态资源；开发环境可不用 |

### 2.5 基础设施

| 项 | 说明 |
|----|------|
| Maven | 3.9.x+，使用 Maven Wrapper（`mvnw`） |
| Docker | 部署用，基础镜像 `eclipse-temurin:17-jre-alpine` |
| Git | 主分支 `main`，开发分支 `dev`，功能分支 `feature/*` |

---

## 三、版本兼容性矩阵

| Spring Boot | Spring Cloud | SCA | RocketMQ Starter | MyBatis-Plus | 状态 |
|-------------|-------------|-----|-----------------|-------------|------|
| 3.2.4 | 2023.0.1 | 2023.0.1.0 | 2.2.3 | 3.5.7（boot3） | **当前使用** |
| 3.5.0 | 2025.0.0 | 2025.0.0.0 | 2.3.2 | 3.5.12（boot3） | 可选升级 |
| 3.4.x | 2024.0.x | 2023.0.x.x | 2.3.1 | 3.5.9（boot3） | 已验证可用 |
| 2.7.x | 2021.0.x | 2021.0.x.x | 2.2.2 | 3.5.3 | **禁止使用** |

---

## 四、父 POM BOM 引入方式

各服务 pom.xml 统一使用以下结构：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.4</version>
    <relativePath/>
</parent>

<properties>
    <java.version>17</java.version>
    <spring-cloud.version>2023.0.1</spring-cloud.version>
    <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
    <rocketmq.version>2.2.3</rocketmq.version>
    <mybatis-plus.version>3.5.7</mybatis-plus.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- Spring Cloud BOM -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- Spring Cloud Alibaba BOM -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>${spring-cloud-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- RocketMQ（不在 BOM 中，需手动锁版本） -->
        <dependency>
            <groupId>org.apache.rocketmq</groupId>
            <artifactId>rocketmq-spring-boot-starter</artifactId>
            <version>${rocketmq.version}</version>
        </dependency>
        <!-- MyBatis-Plus（不在 BOM 中，需手动锁版本） -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 五、各服务不应自行指定版本的依赖

以下依赖版本由父 POM 或 BOM 管理，子服务 **禁止写 `<version>`**：

| 依赖 | 版本来源 |
|------|----------|
| spring-boot-starter-* | Boot 父 POM |
| spring-cloud-starter-gateway | Spring Cloud BOM |
| spring-cloud-starter-openfeign | Spring Cloud BOM |
| spring-cloud-starter-loadbalancer | Spring Cloud BOM |
| spring-cloud-starter-alibaba-nacos-discovery | SCA BOM |
| spring-cloud-starter-alibaba-nacos-config | SCA BOM |
| spring-cloud-starter-alibaba-sentinel | SCA BOM |
| spring-boot-starter-data-redis | Boot 父 POM |
| org.postgresql:postgresql | Boot 父 POM |
| com.mysql:mysql-connector-j | Boot 父 POM |
| spring-boot-starter-test | Boot 父 POM |
| lombok | Boot 父 POM |
| jackson-* | Boot 父 POM |

**需要手动指定版本的（不在任何 BOM 中）：**

| 依赖 | 统一版本 | 说明 |
|------|----------|------|
| rocketmq-spring-boot-starter | 2.2.3 | Apache 独立发布 |
| mybatis-plus-spring-boot3-starter | 3.5.7 | .baomidou 独立发布 |
| hutool-all | 5.8.34 | 如使用，统一锁定 |
| jjwt | 0.12.6 | JWT 库，gateway + common-security 用 |
