# 微服务系列 07 - 熔断与容错

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么需要熔断与容错？

在微服务架构中，服务之间的调用是串行的，一个服务的故障可能引发级联失败（雪崩效应）：

```
正常情况：
User → API Gateway → Order Service → User Service → ✅
                                    → Product Service → ✅

雪崩效应：
User → API Gateway → Order Service → User Service → ✅
                                    → Product Service → ⏳ 超时
                                                          │
                                      Order Service 线程池耗尽
                                                          │
                                      API Gateway 线程池耗尽
                                                          │
                                      整个系统不可用！❌
```

### 1.1 容错模式

| 模式 | 说明 | 类比 |
|------|------|------|
| **超时** | 设置调用超时时间，避免无限等待 | 等公交车最多等30分钟 |
| **熔断** | 错误率达到阈值时，快速失败 | 保险丝烧断，保护电路 |
| **降级** | 服务不可用时返回兜底结果 | 外卖超时给优惠券 |
| **限流** | 限制请求速率，保护系统 | 游乐园限流排队 |
| **隔离** | 隔离不同依赖，防止互相影响 | 船舱隔离，一舱进水不沉船 |

---

## 2. 熔断器模式（Circuit Breaker）

### 2.1 熔断器状态机

```
         成功率恢复
     ┌──────────────────────┐
     │                      │
     ▼                      │
┌─────────┐  失败率超阈值  ┌─────────┐  超时后放行  ┌─────────┐
│ CLOSED  │─────────────▶│  OPEN   │─────────────▶│HALF-OPEN│
│ (正常)   │              │ (熔断)   │              │ (半开)   │
└─────────┘              └─────────┘              └─────────┘
     ▲                                                   │
     │              测试请求成功                           │
     └───────────────────────────────────────────────────┘
     │              测试请求失败
     └──────────────────────┐
                            ▼
                     回到 OPEN 状态

CLOSED：正常放行请求，同时统计失败率
OPEN：直接快速失败，不发起真实调用
HALF-OPEN：放行少量请求测试，判断是否恢复
```

### 2.2 熔断器配置参数

| 参数 | 说明 | 推荐值 |
|------|------|--------|
| **failureRateThreshold** | 失败率阈值（百分比） | 50% |
| **slidingWindowSize** | 滑动窗口大小 | 100 |
| **minimumNumberOfCalls** | 最小调用次数（达到后才计算失败率） | 10 |
| **waitDurationInOpenState** | OPEN 状态持续时间 | 60s |
| **permittedNumberOfCallsInHalfOpenState** | HALF_OPEN 放行次数 | 5 |
| **slowCallRateThreshold** | 慢调用率阈值 | 80% |
| **slowCallDurationThreshold** | 慢调用时间阈值 | 3s |

---

## 3. 实战：Resilience4j

### 3.1 依赖

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-feign</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

### 3.2 熔断配置

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:                           # 默认配置
        sliding-window-type: COUNT_BASED  # 基于计数的滑动窗口
        sliding-window-size: 100          # 窗口大小
        minimum-number-of-calls: 10       # 最小调用次数
        failure-rate-threshold: 50        # 失败率阈值 50%
        slow-call-rate-threshold: 80      # 慢调用率阈值
        slow-call-duration-threshold: 3s  # 慢调用时间阈值
        wait-duration-in-open-state: 60s  # OPEN 持续时间
        permitted-number-of-calls-in-half-open-state: 5
        automatic-transition-from-open-to-half-open-enabled: true
    instances:
      user-service:                       # 针对用户服务的熔断
        base-config: default
        failure-rate-threshold: 60        # 覆盖默认值
      product-service:
        base-config: default
        wait-duration-in-open-state: 30s
```

### 3.3 使用熔断器

```java
@Service
public class OrderServiceImpl {

    @Autowired
    private UserClient userClient;

    @Autowired
    private ProductClient productClient;

    @CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
    public User getUser(Long userId) {
        return userClient.getUserById(userId);
    }

    // 降级方法
    private User getUserFallback(Long userId, Exception e) {
        log.warn("User service fallback triggered for user {}: {}", userId, e.getMessage());
        return User.builder()
            .id(userId)
            .name("服务暂时不可用")
            .build();
    }

    @CircuitBreaker(name = "product-service", fallbackMethod = "getProductFallback")
    public Product getProduct(Long productId) {
        return productClient.getProductById(productId);
    }

    private Product getProductFallback(Long productId, Exception e) {
        log.warn("Product service fallback for product {}: {}", productId, e.getMessage());
        return Product.builder()
            .id(productId)
            .name("商品信息暂时不可用")
            .price(BigDecimal.ZERO)
            .build();
    }
}
```

### 3.4 限流（Rate Limiter）

```yaml
resilience4j:
  ratelimiter:
    configs:
      default:
        limit-for-period: 10        # 每个周期允许的请求数
        limit-refresh-period: 1s    # 周期
        timeout-duration: 0         # 等待获取许可的超时时间
    instances:
      order-create:
        base-config: default
        limit-for-period: 5         # 创建订单每秒最多 5 次
```

```java
@RateLimiter(name = "order-create", fallbackMethod = "createOrderRateLimitFallback")
public Order createOrder(OrderRequest request) {
    // 创建订单逻辑
}

private Order createOrderRateLimitFallback(OrderRequest request, Exception e) {
    throw new ServiceException("系统繁忙，请稍后重试");
}
```

### 3.5 重试（Retry）

```yaml
resilience4j:
  retry:
    configs:
      default:
        max-attempts: 3              # 最大重试次数（含首次）
        wait-duration: 1s            # 重试间隔
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
    instances:
      user-service:
        base-config: default
        max-attempts: 2
```

```java
@Retry(name = "user-service", fallbackMethod = "getUserRetryFallback")
public User getUserWithRetry(Long userId) {
    return userClient.getUserById(userId);
}
```

### 3.6 隔离（Bulkhead）

```yaml
resilience4j:
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 20       # 最大并发数
        max-wait-duration: 0           # 等待超时
    instances:
      order-service:
        max-concurrent-calls: 50
      product-service:
        max-concurrent-calls: 30
```

```java
@Bulkhead(name = "order-service", fallbackMethod = "orderBulkheadFallback")
public Order createOrder(OrderRequest request) {
    // 创建订单逻辑
}
```

### 3.7 组合使用

```java
@Service
public class OrderServiceImpl {

    @CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
    @RateLimiter(name = "user-service")
    @Retry(name = "user-service")
    @Bulkhead(name = "user-service")
    public User getUser(Long userId) {
        return userClient.getUserById(userId);
    }
}
```

---

## 4. 实战：Sentinel（阿里巴巴）

### 4.1 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

### 4.2 配置

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080    # Sentinel 控制台地址
        port: 8719                   # 客户端与控制台通信端口
      eager: true                    # 服务启动立即注册
```

### 4.3 流控规则

```java
@SentinelResource(value = "getUser", blockHandler = "getUserBlockHandler",
                  fallback = "getUserFallback")
public User getUser(Long userId) {
    return userClient.getUserById(userId);
}

// 流控降级（被 Sentinel 拦截时）
public User getUserBlockHandler(Long userId, BlockException ex) {
    throw new ServiceException("请求被限流");
}

// 异常降级（业务异常时）
public User getUserFallback(Long userId, Throwable ex) {
    return User.builder().id(userId).name("服务降级").build();
}
```

### 4.4 Sentinel 规则配置（Nacos 持久化）

```yaml
spring:
  cloud:
    sentinel:
      datasource:
        flow:
          nacos:
            server-addr: localhost:8848
            namespace: sentinel
            group-id: SENTINEL_GROUP
            data-id: ${spring.application.name}-flow-rules
            rule-type: flow
        degrade:
          nacos:
            server-addr: localhost:8848
            namespace: sentinel
            group-id: SENTINEL_GROUP
            data-id: ${spring.application.name}-degrade-rules
            rule-type: degrade
```

---

## 5. Resilience4j vs Sentinel

| 特性 | Resilience4j | Sentinel |
|------|-------------|----------|
| **语言** | Java | Java |
| **熔断** | ✅ | ✅ |
| **限流** | ✅ | ✅（更强大） |
| **系统保护** | ❌ | ✅ |
| **热点限流** | ❌ | ✅ |
| **控制台** | ❌ | ✅ |
| **规则持久化** | ❌ | Nacos/ZooKeeper |
| **响应式** | ✅ | ✅ |
| **轻量级** | ✅ | 中等 |
| **Spring Cloud集成** | 原生 | Alibaba |

---

## 6. 容错设计原则

1. **失败是常态**：假设任何依赖都可能失败
2. **快速失败优于缓慢等待**：超时机制 + 熔断
3. **优雅降级**：返回兜底结果而非错误
4. **限制资源使用**：隔离 + 限流
5. **重试要有上限**：避免重试风暴
6. **监控告警**：熔断状态变更要告警
7. **混沌工程**：主动注入故障验证容错能力

---

**上一篇：** [06 - 配置管理](./06-configuration-management.md)  
**下一篇：** [08 - 分布式追踪](./08-distributed-tracing.md)
