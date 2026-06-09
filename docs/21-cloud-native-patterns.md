# 微服务系列 21 - 云原生设计模式

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 什么是云原生？

云原生（Cloud Native）是一套构建和运行应用的方法论，核心是让应用充分利用云计算的弹性、分布式和自动化能力。

```
云原生 = 微服务 + 容器化 + 动态编排 + 持续交付 + 可观测性

CNCF 云原生全景图（简化）：
┌─────────────────────────────────────────────────────────┐
│                      应用层                               │
│  微服务 │ Serverless │ 函数计算 │ 事件驱动                │
├─────────────────────────────────────────────────────────┤
│                    调度与编排                              │
│  Kubernetes │ Helm │ ArgoCD │ Crossplane                │
├─────────────────────────────────────────────────────────┤
│                   服务网格与网络                           │
│  Istio │ Linkerd │ Envoy │ CoreDNS │ CNI                │
├─────────────────────────────────────────────────────────┤
│                    可观测性                                │
│  Prometheus │ Grafana │ Jaeger │ Fluentd │ OpenTelemetry│
├─────────────────────────────────────────────────────────┤
│                     安全                                  │
│  Vault │ Cert-Manager │ OPA │ Falco                     │
├─────────────────────────────────────────────────────────┤
│                    存储与数据库                            │
│  MySQL │ PostgreSQL │ Redis │ Kafka │ S3               │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 云原生设计模式概览

| 模式 | 解决的问题 | 一句话描述 |
|------|-----------|-----------|
| **Sidecar** | 横切关注点 | 附加容器处理非业务逻辑 |
| **Ambassador** | 外部服务通信 | 统一代理外部服务调用 |
| **Circuit Breaker** | 级联故障 | 快速失败保护系统 |
| **Bulkhead** | 资源隔离 | 隔离故障防止蔓延 |
| **Retry + Backoff** | 瞬时故障 | 自动重试但要有退避 |
| **Timeout** | 无限等待 | 所有调用必须设超时 |
| **Health Check** | 故障检测 | 主动探测服务健康状态 |
| **External Configuration** | 配置耦合 | 配置外置到环境变量/配置中心 |
| **Graceful Degradation** | 部分不可用 | 降级而非崩溃 |
| **Leader Election** | 多实例竞争 | 选主避免重复执行 |

---

## 3. Sidecar 模式

### 3.1 模式说明

将辅助功能（日志、监控、安全、配置）放在独立容器中，与主应用容器同 Pod 部署：

```
┌──────────────── Pod ─────────────────┐
│                                       │
│  ┌───────────┐    ┌────────────────┐ │
│  │ Main App  │    │   Sidecar      │ │
│  │ (业务逻辑) │◀──▶│ (辅助功能)     │ │
│  │ Port:8080 │    │ 日志/监控/代理  │ │
│  └───────────┘    └────────────────┘ │
│                                       │
│  共享：网络命名空间 + 存储卷           │
└───────────────────────────────────────┘
```

### 3.2 K8s Sidecar 示例

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: user-service-pod
spec:
  containers:
    # 主应用容器
    - name: user-service
      image: myregistry/user-service:1.0.0
      ports:
        - containerPort: 8081
      volumeMounts:
        - name: logs
          mountPath: /app/logs

    # Sidecar：日志收集
    - name: log-collector
      image: fluent/fluentd:latest
      volumeMounts:
        - name: logs
          mountPath: /app/logs
      env:
        - name: FLUENT_ELASTICSEARCH_HOST
          value: "elasticsearch"

  volumes:
    - name: logs
      emptyDir: {}
```

### 3.3 Sidecar 适用场景

| 场景 | Sidecar 类型 |
|------|-------------|
| **日志收集** | Fluentd / Filebeat |
| **监控代理** | Prometheus Exporter |
| **网络代理** | Envoy / Istio |
| **配置热更新** | Confd / Vault Agent |
| **安全代理** | Keycloak Proxy |

---

## 4. Ambassador 模式

### 4.1 模式说明

用一个 Ambassador 容器统一代理对外部服务的调用，实现重试、安全、缓存等：

```
┌──────────────── Pod ─────────────────┐
│                                       │
│  ┌───────────┐    ┌────────────────┐ │
│  │ Main App  │    │  Ambassador    │ │
│  │           │───▶│  (代理)        │──▶ External Service
│  │localhost  │    │  重试/限流/缓存│    (MySQL/S3/API)
│  │:8080      │    │               │
│  └───────────┘    └────────────────┘ │
└───────────────────────────────────────┘
```

### 4.2 示例：数据库代理

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: user-service-with-ambassador
spec:
  containers:
    - name: user-service
      image: myregistry/user-service:1.0.0
      env:
        - name: DB_HOST
          value: "localhost"    # 连接本地 Ambassador
        - name: DB_PORT
          value: "3306"

    # Ambassador：数据库代理
    - name: db-ambassador
      image: prom/mysqld-exporter:latest
      # ProxySQL 配置：连接池、读写分离、重试
```

---

## 5. 健康检查模式

### 5.1 三种探针

```
Startup Probe（启动探针）：
├── 应用启动期间使用
├── 失败次数多也不重启（给慢启动应用时间）
└── 成功后才切换到 Liveness

Liveness Probe（存活探针）：
├── 应用运行期间持续检查
├── 失败 → 重启容器
└── 检测死锁、线程耗尽等

Readiness Probe（就绪探针）：
├── 检查是否准备好接收流量
├── 失败 → 从 Service 端点列表中移除
└── 检测依赖不可用、缓存未预热等
```

### 5.2 Spring Boot Actuator 健康检查

```java
@Component
public class CustomHealthIndicator extends AbstractHealthIndicator {

    @Autowired private UserRepository userRepository;
    @Autowired private UserClient userClient;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        // Liveness: JVM 状态
        builder.up();

        // Readiness: 依赖检查
        try {
            userRepository.count();     // 数据库连接
            builder.withDetail("database", "UP");
        } catch (Exception e) {
            builder.down().withDetail("database", "DOWN: " + e.getMessage());
        }

        try {
            userClient.ping();          // 下游服务
            builder.withDetail("userClient", "UP");
        } catch (Exception e) {
            builder.down().withDetail("userClient", "DOWN");
        }
    }
}
```

```yaml
# K8s 探针配置
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  failureThreshold: 30
  periodSeconds: 5     # 最多等 150 秒启动

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 60
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 5
```

---

## 6. 优雅上下线

### 6.1 优雅停机

```yaml
# application.yml
server:
  shutdown: graceful        # 启用优雅停机

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 最多等 30 秒
```

```yaml
# K8s 生命周期钩子
spec:
  containers:
    - name: user-service
      lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 10"]  # 等待 K8s 更新 Service 端点
  terminationGracePeriodSeconds: 45             # 总宽限期
```

### 6.2 启动顺序控制

```
启动顺序：
1. 基础设施（DB、MQ、Redis）
2. 注册中心（Eureka/Nacos）
3. 配置中心
4. 业务服务（无依赖的先启动）
5. 依赖其他服务的后启动
6. API Gateway

K8s 中用 initContainer 或 readinessProbe 控制依赖：

spec:
  initContainers:
    - name: wait-for-eureka
      image: busybox
      command: ['sh', '-c', 'until nc -z eureka-server 8761; do echo waiting; sleep 2; done']
```

---

## 7. External Configuration 模式

### 7.1 十二因素应用

```
III. Config：在环境中存储配置

配置来源（优先级从高到低）：
1. 命令行参数
2. 环境变量
3. K8s ConfigMap / Secret
4. 配置中心（Nacos/Config Server）
5. 本地配置文件

关键原则：
- 不在代码中硬编码配置
- 敏感信息用 Secret/Vault
- 环境之间只改配置，不改代码
```

### 7.2 K8s ConfigMap + Secret

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: user-service-config
data:
  SPRING_PROFILES_ACTIVE: "prod"
  EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE: "http://eureka-server:8761/eureka/"
  DB_HOST: "mysql.prod"
  DB_PORT: "3306"
  DB_NAME: "user_db"
---
apiVersion: v1
kind: Secret
metadata:
  name: user-service-secret
type: Opaque
data:
  DB_USERNAME: dXNlcmFkbWlu     # base64
  DB_PASSWORD: c2VjcmV0cGFzc3dvcmQ=  # base64
```

---

## 8. Leader Election 模式

### 8.1 场景

多实例部署时，某些任务只需要一个实例执行（如定时任务、数据同步）：

```
3 个 Order Service 实例，但定时任务只需要执行一次：

Instance 1: LEADER ✅ → 执行定时任务
Instance 2: FOLLOWER → 不执行
Instance 3: FOLLOWER → 不执行

Instance 1 挂了 → 重新选主 → Instance 2 变为 LEADER
```

### 8.2 K8s Leader Election

```java
@Configuration
public class LeaderElectionConfig {

    @Bean
    @ConditionalOnProperty(name = "leader-election.enabled", havingValue = "true")
    public LeaderInitiator leaderInitiator(LockRegistry lockRegistry) {
        DefaultCandidate candidate = new DefaultCandidate();
        return new DefaultLeaderInitiator(lockRegistry, candidate);
    }
}

@Component
@RequiredArgsConstructor
public class ScheduledTaskRunner {

    private final LeaderInitiator leaderInitiator;

    @Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨 2 点
    public void runDailyTask() {
        if (leaderInitiator.getContext().isLeader()) {
            log.info("I am the leader, executing daily task");
            // 只有 Leader 执行
        }
    }
}
```

---

## 9. 模式速查表

| 模式 | 解决方案 | K8s 实现 |
|------|---------|----------|
| **Sidecar** | 辅助功能分离 | 同 Pod 多容器 |
| **Ambassador** | 外部服务代理 | Proxy Sidecar |
| **Circuit Breaker** | 级联故障 | Resilience4j / Istio |
| **Bulkhead** | 资源隔离 | 线程池 / 进程隔离 |
| **Health Check** | 故障检测 | Liveness/Readiness Probe |
| **Graceful Shutdown** | 平滑停机 | preStop Hook + 优雅关闭 |
| **External Config** | 配置外置 | ConfigMap / Secret / Nacos |
| **Leader Election** | 选主 | K8s Lease / Spring Integration |
| **Retry + Backoff** | 瞬时故障 | Resilience4j Retry |
| **Timeout** | 无限等待 | 全局超时配置 |

---

**上一篇：** [20 - BFF 与 API 聚合](./20-bff-api-aggregation.md)  
**下一篇：** [22 - 生产就绪检查清单](./22-production-readiness.md)