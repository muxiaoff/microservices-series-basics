# 微服务系列 03 - 服务注册与发现

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么需要服务注册与发现？

在微服务架构中，服务实例的地址（IP + 端口）是动态变化的：

- 服务可能随时扩缩容
- 容器化部署后 IP 每次重启都变
- 服务可能有多个实例做负载均衡

**没有注册中心时：**

```properties
# 硬编码服务地址，维护噩梦
order-service.url=http://192.168.1.100:8082
order-service.url=http://192.168.1.101:8082
# 新增实例？修改配置？重启？？
```

**有注册中心时：**

```
服务启动 → 自动注册到注册中心
服务停止 → 自动从注册中心注销
调用方   → 从注册中心获取最新地址列表
```

---

## 2. 核心概念

### 2.1 服务注册（Service Registration）

服务实例启动时，将自己的信息（IP、端口、服务名、元数据）注册到注册中心。

```
┌──────────┐  注册   ┌──────────────┐
│ Service A │───────▶│              │
└──────────┘        │   Registry   │
┌──────────┐  注册   │   (Eureka)   │
│ Service B │───────▶│              │
└──────────┘        └──────────────┘
```

### 2.2 服务发现（Service Discovery）

调用方从注册中心获取目标服务的实例列表。

```
┌──────────┐  查询   ┌──────────────┐
│ Service A │───────▶│   Registry   │
│ (调用方)   │◀───────│   (Eureka)   │
└──────────┘  返回   │              │
     │          列表   └──────────────┘
     ▼
  选择一个实例发起调用
```

### 2.3 心跳检测（Heartbeat）

注册中心通过心跳机制检测服务实例是否存活，自动剔除不健康的实例。

```
Service A ──(心跳)──▶ Registry  ✓ 存活
Service B ──(超时)──▶ Registry  ✗ 剔除
Service C ──(心跳)──▶ Registry  ✓ 存活
```

---

## 3. 主流注册中心对比

| 特性 | Eureka | Nacos | Consul | Zookeeper |
|------|--------|-------|--------|-----------|
| **CAP** | AP | AP/CP | CP | CP |
| **健康检查** | 客户端心跳 | 心跳/TCP/HTTP | Agent/HTTP/TCP | 长连接/Session |
| **配置管理** | ❌ | ✅ | ✅ | ❌ |
| **多数据中心** | ❌ | ✅ | ✅ | ❌ |
| **访问协议** | HTTP | HTTP/gRPC | HTTP/DNS | TCP |
| **Spring Cloud集成** | 原生 | Alibaba | 社区 | 社区 |
| **运维复杂度** | 低 | 中 | 中 | 高 |
| **适用场景** | 简单场景 | 国内主流 | 多数据中心 | Dubbo 传统 |

### 3.1 CAP 定理与注册中心选择

```
CAP 定理：分布式系统不可能同时满足以下三点

一致性 (Consistency)  ────  可用性 (Availability)
           ╲                    ╱
            ╲                  ╱
             ╲                ╱
              分区容错性 (Partition tolerance)

在 P 必然存在的情况下：
- CP 系统：保证一致性，牺牲可用性（ZooKeeper、Consul）
- AP 系统：保证可用性，牺牲一致性（Eureka）

注册中心的选择：
- 优先可用性 → Eureka（服务发现场景，短暂不一致可接受）
- 优先一致性 → Consul/ZooKeeper（强一致性要求高）
- 两者兼顾 → Nacos（可切换 AP/CP 模式）
```

---

## 4. 实战：Eureka Server

### 4.1 创建 Eureka Server

**Maven 依赖：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>
```

**启动类：**

```java
package com.example.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer  // 启用 Eureka Server
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

**配置文件 application.yml：**

```yaml
server:
  port: 8761

spring:
  application:
    name: eureka-server

eureka:
  instance:
    hostname: localhost
  client:
    # 不向自己注册（单机模式）
    register-with-eureka: false
    # 不从自己拉取注册信息
    fetch-registry: false
    service-url:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/
  server:
    # 自我保护模式（开发环境可以关闭）
    enable-self-preservation: false
    # 清理无效节点间隔（毫秒）
    eviction-interval-timer-in-ms: 5000
```

### 4.2 Eureka 自我保护机制

Eureka 的自我保护机制是 AP 模式的体现：

```
当 Eureka Server 在一定时间内没有收到超过一定比例的心跳时，
会进入自我保护模式，不再剔除服务实例。

为什么？可能是网络问题导致心跳丢失，而非服务真的挂了。
宁可保留可能不存在的服务（假阳性），也不删除仍然存活的服务（假阴性）。

生产环境：建议开启（默认开启）
开发环境：可以关闭，方便测试
```

### 4.3 Eureka 高可用集群

```
┌──────────────┐    复制    ┌──────────────┐
│ Eureka Node1 │──────────▶│ Eureka Node2 │
│  (8761)      │◀──────────│  (8762)      │
└──────────────┘    复制    └──────────────┘
        ▲                          ▲
        │        注册/查询           │
        └──────────┬───────────────┘
                   │
            各个微服务实例
```

Node1 配置：

```yaml
server:
  port: 8761

eureka:
  instance:
    hostname: eureka-node1
  client:
    register-with-eureka: true
    fetch-registry: true
    service-url:
      defaultZone: http://eureka-node2:8762/eureka/
```

Node2 配置：

```yaml
server:
  port: 8762

eureka:
  instance:
    hostname: eureka-node2
  client:
    register-with-eureka: true
    fetch-registry: true
    service-url:
      defaultZone: http://eureka-node1:8761/eureka/
```

---

## 5. 实战：服务注册到 Eureka

### 5.1 添加依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

### 5.2 配置注册

```yaml
server:
  port: 8081

spring:
  application:
    name: user-service  # 服务名，注册到 Eureka 的标识

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    # 使用 IP 注册（而非主机名）
    prefer-ip-address: true
    # 实例 ID 格式
    instance-id: ${spring.application.name}:${spring.cloud.client.ip-address}:${server.port}
    # 心跳间隔（秒）
    lease-renewal-interval-in-seconds: 10
    # 失效时间（秒），超过此时间未收到心跳则剔除
    lease-expiration-duration-in-seconds: 30
```

### 5.3 启动类（可选注解）

```java
@SpringBootApplication
// Spring Cloud Edgware+ 自动注册，无需 @EnableEurekaClient
// 但显式声明更清晰
@EnableEurekaClient
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

---

## 6. 实战：Nacos 注册中心

### 6.1 Nacos 简介

Nacos（Naming and Configuration Service）是阿里巴巴开源的注册中心 + 配置中心，在国内使用广泛。

**优势：**
- 同时支持 AP 和 CP 模式（临时实例用 AP，持久实例用 CP）
- 自带配置管理功能
- 支持 DNS 和 HTTP 服务发现
- 有管理控制台

### 6.2 使用 Nacos

**依赖：**

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

**配置：**

```yaml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
        namespace: dev
        group: DEFAULT_GROUP
```

### 6.3 Nacos vs Eureka

| 对比项 | Eureka | Nacos |
|--------|--------|-------|
| 一致性模型 | 仅 AP | AP + CP |
| 配置管理 | 无 | 有 |
| 健康检查 | 客户端心跳 | 心跳 + 服务端主动检测 |
| 雪崩保护 | 自我保护模式 | 阈值保护 |
| 实例类型 | 仅临时实例 | 临时 + 持久实例 |
| 管理界面 | 简单 | 丰富 |
| 社区活跃度 | 维护模式 | 活跃 |

---

## 7. 负载均衡

### 7.1 客户端负载均衡 vs 服务端负载均衡

```
服务端负载均衡（Nginx）：
Client → Nginx(负载均衡) → Service A1 / A2 / A3

客户端负载均衡（Spring Cloud LoadBalancer）：
Client(内置负载均衡) → Service A1 / A2 / A3
```

### 7.2 Spring Cloud LoadBalancer

Spring Cloud 2020+ 已用 Spring Cloud LoadBalancer 替代了 Ribbon。

**依赖：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

**使用 RestTemplate + 负载均衡：**

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced  // 开启负载均衡
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

// 使用时直接用服务名替代 IP:端口
@Service
public class OrderService {

    @Autowired
    private RestTemplate restTemplate;

    public User getUser(Long userId) {
        // 使用服务名 user-service，LoadBalancer 自动选择实例
        return restTemplate.getForObject(
            "http://user-service/api/users/" + userId,
            User.class
        );
    }
}
```

**自定义负载均衡策略：**

```java
@Configuration
public class LoadBalancerConfig {

    @Bean
    ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory factory) {
        String name = environment.getProperty(
            LoadBalancerClientFactory.PROPERTY_NAME);
        return new RandomLoadBalancer(
            factory.getLazyProvider(name, ServiceInstanceListSupplier.class),
            name
        );
    }
}
```

---

## 8. 完整流程

```
1. 启动 Eureka Server (8761)
2. 启动 User Service (8081) → 自动注册到 Eureka
3. 启动 Order Service (8082) → 自动注册到 Eureka
4. Order Service 调用 User Service：
   a. LoadBalancer 从 Eureka 获取 user-service 实例列表
   b. 选择一个实例（轮询/随机）
   c. 发起 HTTP 调用
5. 访问 Eureka 控制台 http://localhost:8761 查看注册的服务
```

---

## 9. 常见问题

### Q1: Eureka 已进入维护模式，还值得学吗？

Eureka 是最简单的注册中心实现，非常适合学习服务注册发现的核心概念。生产环境推荐使用 Nacos 或 Consul。

### Q2: 服务下线时如何优雅处理？

```java
// 方式 1：发送 shutdown 请求
// POST http://localhost:8081/actuator/shutdown

// 方式 2：程序中主动下线
@Autowired
private EurekaClient eurekaClient;

public void gracefulShutdown() {
    eurekaClient.shutdown();  // 主动从 Eureka 注销
}
```

### Q3: 如何处理注册中心宕机？

- Eureka 客户端有本地缓存，短时间可用
- 部署多节点 Eureka 集群
- 考虑使用 Nacos 等支持集群的注册中心

---

**上一篇：** [02 - 架构设计](./02-architecture-design.md)  
**下一篇：** [04 - API 网关](./04-api-gateway.md)
