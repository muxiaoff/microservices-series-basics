# 微服务系列 06 - 配置管理

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么需要配置中心？

没有配置中心时：

```
❌ 配置散落在各个服务中
┌──────────┐  ┌──────────┐  ┌──────────┐
│User Svc  │  │Order Svc │  │Product Svc│
│app.yml   │  │app.yml   │  │app.yml   │
│dev.yml   │  │dev.yml   │  │dev.yml   │
│prod.yml  │  │prod.yml  │  │prod.yml  │
└──────────┘  └──────────┘  └──────────┘

问题：
- 修改配置需要重启服务
- 多环境配置难以管理
- 配置变更没有审计
- 敏感信息（密码）明文存储
- 配置无法集中管理
```

有配置中心后：

```
✅ 集中管理所有配置
┌─────────────────────┐
│    Config Center     │
│  ┌────────────────┐ │
│  │ user-service/   │ │
│  │   application.yml│ │
│  │   dev.yml       │ │
│  │   prod.yml      │ │
│  │ order-service/  │ │
│  │   application.yml│ │
│  │ product-service/│ │
│  │   application.yml│ │
│  └────────────────┘ │
└──────────┬──────────┘
           │ 推送/拉取
    ┌──────┼──────┐
    ▼      ▼      ▼
  User   Order  Product
  Svc    Svc    Svc
```

---

## 2. 配置中心对比

| 特性 | Spring Cloud Config | Nacos Config | Apollo | Consul |
|------|---------------------|-------------|--------|--------|
| **实时推送** | 需 Bus | ✅ | ✅ | ✅ |
| **管理界面** | ❌ | ✅ | ✅ | ✅ |
| **灰度发布** | ❌ | ✅ | ✅ | ❌ |
| **权限管理** | ❌ | ✅ | ✅ | ✅ |
| **多环境** | ✅ | ✅ | ✅ | ✅ |
| **版本回滚** | Git | ✅ | ✅ | ✅ |
| **适用场景** | 简单项目 | 国内主流 | 大型企业 | 多功能 |

---

## 3. 实战：Spring Cloud Config

### 3.1 Config Server

**依赖：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-config-server</artifactId>
</dependency>
```

**启动类：**

```java
@SpringBootApplication
@EnableConfigServer  // 启用配置服务器
public class ConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

**配置 application.yml：**

```yaml
server:
  port: 8888

spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        git:
          uri: https://github.com/your-org/microservices-config.git
          search-paths: '{application}'
          default-label: main
          username: ${GIT_USERNAME}
          password: ${GIT_PASSWORD}
```

**Git 仓库结构：**

```
microservices-config/
├── user-service/
│   ├── application.yml         # 共享配置
│   ├── application-dev.yml     # 开发环境
│   ├── application-prod.yml    # 生产环境
│   └── application-test.yml    # 测试环境
├── order-service/
│   ├── application.yml
│   └── application-dev.yml
└── product-service/
    ├── application.yml
    └── application-dev.yml
```

### 3.2 Config Client

**依赖：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

**配置 bootstrap.yml（优先于 application.yml 加载）：**

```yaml
spring:
  application:
    name: user-service
  cloud:
    config:
      uri: http://localhost:8888
      label: main
      profile: dev         # 环境标识
      fail-fast: true      # 连接失败快速启动失败
```

### 3.3 动态刷新配置

**方式一：手动刷新**

```java
@RestController
@RefreshScope  // 支持配置刷新
public class ConfigController {

    @Value("${app.max-connections:100}")
    private int maxConnections;

    @GetMapping("/config/max-connections")
    public int getMaxConnections() {
        return maxConnections;
    }
}
```

```bash
# 触发配置刷新
curl -X POST http://localhost:8081/actuator/refresh
```

**方式二：Spring Cloud Bus + RabbitMQ 自动推送**

```
Git Push → Webhook → Config Server → RabbitMQ → 所有服务自动刷新
```

---

## 4. 实战：Nacos Config（推荐）

### 4.1 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

### 4.2 配置

**bootstrap.yml：**

```yaml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      server-addr: localhost:8848
      config:
        namespace: dev                    # 命名空间
        group: DEFAULT_GROUP              # 分组
        file-extension: yaml              # 配置文件格式
        shared-configs:                   # 共享配置
          - data-id: common-db.yaml
            group: DEFAULT_GROUP
            refresh: true
          - data-id: common-redis.yaml
            group: DEFAULT_GROUP
            refresh: true
```

### 4.3 Nacos 配置管理

```
Nacos 配置层级：

Namespace（命名空间）→ Group（分组）→ Data ID（配置集）

示例：
- Namespace: dev / test / prod（环境隔离）
  - Group: DEFAULT_GROUP / TRADE_GROUP
    - Data ID: user-service.yaml
    - Data ID: common-db.yaml
```

### 4.4 动态配置监听

```java
@Component
@Slf4j
public class DynamicConfigListener {

    @NacosConfigListener(dataId = "user-service.yaml", groupId = "DEFAULT_GROUP")
    public void onConfigChange(String newConfig) {
        log.info("Config changed: {}", newConfig);
        // 处理配置变更
    }
}
```

```java
@Configuration
@RefreshScope
public class DynamicConfig {

    @Value("${app.feature.enabled:false}")
    private boolean featureEnabled;

    @Value("${app.cache.ttl:3600}")
    private long cacheTtl;

    // Getter...
}
```

---

## 5. 配置最佳实践

### 5.1 配置分层

```
优先级从高到低：

1. 命令行参数          → java -jar app.jar --server.port=8081
2. 环境变量            → SERVER_PORT=8081
3. 配置中心（profile）  → user-service-prod.yaml
4. 配置中心（默认）     → user-service.yaml
5. 本地配置（profile）  → application-prod.yml
6. 本地配置（默认）     → application.yml
```

### 5.2 敏感配置管理

```
❌ 不要这样做：
database.password=MySecret123  # 明文存储

✅ 推荐做法：
1. 使用 Nacos 的加密配置功能
2. 使用 Vault 管理密钥
3. 使用环境变量注入
4. 使用 KMS（密钥管理服务）
```

```yaml
# Nacos 加密配置
jasypt:
  encryptor:
    password: ${JASYPT_PASSWORD}  # 从环境变量获取

# 配置中使用加密值
database:
  password: ENC(加密后的密文)
```

### 5.3 多环境配置

```
推荐方案：

开发环境 (dev)
├── Namespace: dev
├── 使用本地数据库
└── 日志级别 DEBUG

测试环境 (test)
├── Namespace: test
├── 使用测试数据库
└── 日志级别 INFO

生产环境 (prod)
├── Namespace: prod
├── 使用生产数据库
└── 日志级别 WARN
```

### 5.4 配置变更审计

- 所有配置变更应有记录
- Nacos 自带配置历史和回滚功能
- 生产环境配置变更需审批流程
- 关键配置变更应触发告警

---

## 6. 配置中心高可用

```
┌──────────────┐     ┌──────────────┐
│ Nacos Node 1 │────▶│ Nacos Node 2 │
│  (8848)      │◀────│  (8849)      │
└──────┬───────┘     └──────────────┘
       │                    │
       └────────┬───────────┘
                │
         ┌──────▼──────┐
         │ Nacos Node 3│
         │  (8850)     │
         └─────────────┘

客户端配置多个地址：
spring.cloud.nacos.server-addr=node1:8848,node2:8849,node3:8850
```

---

**上一篇：** [05 - 服务间通信](./05-service-communication.md)  
**下一篇：** [07 - 熔断与容错](./07-circuit-breaker-resilience.md)
