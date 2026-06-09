# 微服务系列 24 - 微服务性能优化

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 微服务性能优化全局视角

单体应用性能调优，往往只关注数据库和代码逻辑。微服务带来了更多的性能瓶颈点：

```
性能瓶颈分布：

客户端
  │
  ▼
API Gateway ←── 瓶颈 1：路由开销、JWT 验证、限流
  │
  ▼
Service A   ←── 瓶颈 2：业务逻辑、JVM 开销
  │
  ├── Feign 调用 → Service B  ←── 瓶颈 3：网络延迟、序列化
  │                                       瓶颈 4：服务 B 处理耗时
  │
  ├── 数据库查询    ←── 瓶颈 5：慢查询、连接池耗尽
  │
  └── 缓存         ←── 瓶颈 6：缓存穿透、击穿、雪崩
```

性能优化的核心方法论：**先测量，后优化**。没有数据支撑的优化是瞎猜。

---

## 2. 服务间通信优化

### 2.1 Feign 连接池配置

默认 Feign 使用 `HttpURLConnection`，不支持连接复用，高并发下性能差。开启 Apache HttpClient / OkHttp 连接池：

```xml
<!-- 推荐使用 OkHttp -->
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-okhttp</artifactId>
</dependency>
```

```yaml
feign:
  okhttp:
    enabled: true
  client:
    config:
      default:
        connect-timeout: 2000      # 连接超时 2s
        read-timeout: 5000         # 读超时 5s
      product-service:
        connect-timeout: 1000
        read-timeout: 3000
```

```java
// OkHttp 连接池配置
@Configuration
@ConditionalOnClass(OkHttpClient.class)
public class FeignOkHttpConfig {

    @Bean
    public okhttp3.OkHttpClient okHttpClient() {
        return new okhttp3.OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(
                200,          // 最大空闲连接数
                5,            // 连接存活时间
                TimeUnit.MINUTES
            ))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    }
}
```

### 2.2 并行调用替代串行调用

订单详情需要同时查询用户信息和商品信息时，**串行调用**总耗时 = 用户服务耗时 + 商品服务耗时：

```java
// ❌ 串行调用：200ms + 150ms = 350ms
public OrderDetailResponse getOrderDetail(Long orderId) {
    Order order = getOrder(orderId);
    UserDTO user = userClient.getUserById(order.getUserId());     // 200ms
    ProductDTO product = productClient.getProductById(order.getProductId()); // 150ms
    return buildResponse(order, user, product);
}
```

改为**并行调用**：总耗时 = max(200ms, 150ms) = 200ms：

```java
// ✅ 并行调用：max(200ms, 150ms) = 200ms
public OrderDetailResponse getOrderDetail(Long orderId) {
    Order order = getOrder(orderId);

    // 并发提交两个任务
    CompletableFuture<UserDTO> userFuture = CompletableFuture
        .supplyAsync(() -> userClient.getUserById(order.getUserId()),
                     virtualThreadExecutor);

    CompletableFuture<ProductDTO> productFuture = CompletableFuture
        .supplyAsync(() -> productClient.getProductById(order.getProductId()),
                     virtualThreadExecutor);

    // 等待两者都完成
    CompletableFuture.allOf(userFuture, productFuture).join();

    return buildResponse(order,
        userFuture.join(),
        productFuture.join());
}
```

### 2.3 Java 21 虚拟线程（Virtual Threads）

Java 21 引入虚拟线程，对于 I/O 密集型的微服务场景（Feign 调用、数据库查询）有显著提升：

```yaml
# Spring Boot 3.2+ 开启虚拟线程
spring:
  threads:
    virtual:
      enabled: true
```

```java
// 手动创建虚拟线程执行器
@Bean("virtualThreadExecutor")
public Executor virtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

开启虚拟线程后，每个请求可以分配一个虚拟线程（内存开销极低，约 1KB），等待 I/O 时不阻塞平台线程，系统吞吐量大幅提升。

---

## 3. 缓存优化

### 3.1 多级缓存架构

```
请求 → L1：本地缓存（Caffeine）→ L2：分布式缓存（Redis）→ 数据库
       命中率最高                 跨服务共享                 最慢
       延迟最低（ns 级）          延迟低（ms 级）             延迟高（10ms+）
```

```java
@Service
public class ProductService {

    // L1：本地缓存（JVM 内，最快）
    private final Cache<Long, Product> localCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(1, TimeUnit.MINUTES)  // 本地缓存时间短，防止数据不一致
        .build();

    @Autowired
    private RedisTemplate<String, Product> redisTemplate;

    @Autowired
    private ProductRepository productRepository;

    public Product getProduct(Long id) {
        // 1. 查 L1 本地缓存
        Product product = localCache.getIfPresent(id);
        if (product != null) {
            return product;
        }

        // 2. 查 L2 Redis 缓存
        String redisKey = "product:" + id;
        product = redisTemplate.opsForValue().get(redisKey);
        if (product != null) {
            localCache.put(id, product);  // 回填本地缓存
            return product;
        }

        // 3. 查数据库
        product = productRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "Product not found"));

        // 回填两级缓存
        redisTemplate.opsForValue().set(redisKey, product, 10, TimeUnit.MINUTES);
        localCache.put(id, product);

        return product;
    }
}
```

### 3.2 缓存穿透：布隆过滤器

缓存穿透：恶意请求查询不存在的数据，每次都穿透到数据库。

```java
@Component
public class BloomFilterGuard {

    // 预计元素数量 100 万，误判率 0.01%
    private final BloomFilter<Long> productFilter =
        BloomFilter.create(Funnels.longFunnel(), 1_000_000, 0.0001);

    @PostConstruct
    public void init() {
        // 启动时将所有商品 ID 加载到布隆过滤器
        productRepository.findAllIds().forEach(productFilter::put);
    }

    public boolean mightExist(Long productId) {
        return productFilter.mightContain(productId);
    }
}

// 使用：在查询前先判断是否可能存在
public Product getProduct(Long id) {
    if (!bloomFilterGuard.mightExist(id)) {
        throw new BusinessException(404, "Product not found");  // 直接拦截
    }
    // 正常缓存 → 数据库查询流程
    ...
}
```

### 3.3 缓存击穿：分布式锁

缓存击穿：热点缓存到期瞬间，大量请求同时穿透到数据库。

```java
public Product getProduct(Long id) {
    String redisKey = "product:" + id;
    Product product = redisTemplate.opsForValue().get(redisKey);
    if (product != null) {
        return product;
    }

    // 使用 Redisson 分布式锁，只允许一个线程重建缓存
    String lockKey = "lock:product:" + id;
    RLock lock = redissonClient.getLock(lockKey);

    try {
        // 等待最多 3 秒，锁自动释放时间 10 秒
        if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
            // 再次检查（双重检查，防止重复重建）
            product = redisTemplate.opsForValue().get(redisKey);
            if (product != null) {
                return product;
            }

            product = productRepository.findById(id).orElseThrow(...);
            redisTemplate.opsForValue().set(redisKey, product, 10, TimeUnit.MINUTES);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    return product;
}
```

### 3.4 缓存雪崩：随机过期时间

大量缓存同时过期，导致瞬间大量请求打到数据库。

```java
// ❌ 所有缓存同时过期
redisTemplate.opsForValue().set(key, value, 10, TimeUnit.MINUTES);

// ✅ 加随机偏移，打散过期时间
long baseExpire = 10;  // 基础过期时间（分钟）
long jitter = ThreadLocalRandom.current().nextLong(0, 5);  // 0~5 分钟随机偏移
redisTemplate.opsForValue().set(key, value, baseExpire + jitter, TimeUnit.MINUTES);
```

---

## 4. 数据库优化

### 4.1 连接池调优（HikariCP）

HikariCP 是目前性能最好的 JDBC 连接池。连接池大小不是越大越好：

```yaml
spring:
  datasource:
    hikari:
      # 核心公式（参考）：最优连接数 ≈ CPU核心数 * 2 + 磁盘数
      maximum-pool-size: 20       # 最大连接数
      minimum-idle: 5             # 最小空闲连接
      connection-timeout: 3000    # 获取连接超时 3s
      idle-timeout: 600000        # 空闲连接存活时间 10min
      max-lifetime: 1800000       # 连接最大存活时间 30min（要小于数据库 wait_timeout）
      keepalive-time: 60000       # 心跳检测间隔
      pool-name: HikariPool-OrderService
      # 慢连接告警（ms），超过此值记录告警日志
      connection-init-sql: SELECT 1
```

### 4.2 读写分离

```yaml
# 主数据源（写）
spring:
  datasource:
    master:
      url: jdbc:mysql://master-host:3306/order_db
      username: root
      password: secret

# 从数据源（读）
  datasource:
    slave:
      url: jdbc:mysql://slave-host:3306/order_db
      username: readonly
      password: secret
```

```java
/**
 * 动态数据源路由：读操作走从库，写操作走主库
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.isReadOnly() ? "slave" : "master";
    }
}

/**
 * AOP：自动识别只读事务，路由到从库
 */
@Aspect
@Component
public class ReadWriteDataSourceAspect {

    @Around("@annotation(transactional)")
    public Object routeDataSource(ProceedingJoinPoint point,
                                   Transactional transactional) throws Throwable {
        boolean readOnly = transactional.readOnly();
        DataSourceContextHolder.setReadOnly(readOnly);
        try {
            return point.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
```

### 4.3 MyBatis-Plus Join 优化查询

配合上一章加入的 `mybatis-plus-join`，关联查询无需多次 Feign 调用：

```java
// 订单列表页：同时展示订单信息和用户名（通常需要两次 Feign 调用）
// 使用 MPJ，一次 JOIN 查询搞定，减少服务间调用
@Mapper
public interface OrderMapper extends MPJBaseMapper<Order> {
}

// 查询：订单 + 用户名 JOIN
List<OrderVO> list = orderMapper.selectJoinList(OrderVO.class,
    new MPJLambdaWrapper<Order>()
        .selectAll(Order.class)
        .select(User::getName, User::getPhone)
        .leftJoin(User.class, User::getId, Order::getUserId)
        .eq(Order::getTenantId, TenantContext.getTenantId())
        .eq(Order::getStatus, "CREATED")
        .orderByDesc(Order::getCreatedAt));
```

---

## 5. JVM 调优

### 5.1 推荐 JVM 启动参数

```bash
java \
  -XX:+UseG1GC \                          # 使用 G1 垃圾回收器
  -XX:MaxGCPauseMillis=200 \              # 目标 GC 停顿不超过 200ms
  -XX:+UseStringDeduplication \           # 字符串去重（减少堆内存）
  -XX:MaxRAMPercentage=75.0 \             # 容器场景：堆大小 = 容器内存 * 75%
  -XX:+HeapDumpOnOutOfMemoryError \       # OOM 时自动生成堆转储
  -XX:HeapDumpPath=/app/logs/heap.hprof \ # 堆转储路径
  -Xss256k \                              # 线程栈大小（虚拟线程场景可减小）
  -Djava.security.egd=file:/dev/./urandom \ # 加快 SecureRandom 初始化
  -jar app.jar
```

### 5.2 GC 监控指标

关注以下 JVM 指标（通过 Prometheus + Grafana 监控）：

| 指标 | 告警阈值 | 说明 |
|------|---------|------|
| `jvm_gc_pause_seconds` | > 500ms | GC 停顿时间过长影响 RT |
| `jvm_memory_used_bytes / max` | > 85% | 堆内存使用率过高 |
| `jvm_gc_memory_promoted_bytes` | 持续增长 | 对象晋升频繁，可能内存泄漏 |
| `jvm_threads_live_threads` | > 500 | 线程数异常（非虚拟线程场景） |

---

## 6. 接口响应时间优化

### 6.1 异步返回结果（Deferred Processing）

对于耗时操作，立即返回任务 ID，客户端轮询或通过 WebSocket 推送结果：

```java
// 下单接口：异步处理，立即返回
@PostMapping("/orders")
public ApiResponse<OrderSubmitResult> submitOrder(@RequestBody OrderRequest request) {
    String taskId = UUID.randomUUID().toString();

    // 异步处理
    CompletableFuture.runAsync(() -> {
        TenantContext.setTenantId(TenantContext.getTenantId());  // 传递上下文
        orderService.processOrder(taskId, request);
    }, virtualThreadExecutor);

    return ApiResponse.success(OrderSubmitResult.builder()
        .taskId(taskId)
        .status("PROCESSING")
        .build());
}

// 查询处理结果
@GetMapping("/orders/tasks/{taskId}")
public ApiResponse<OrderTask> queryTask(@PathVariable String taskId) {
    return ApiResponse.success(orderTaskService.getTask(taskId));
}
```

### 6.2 请求合并（Request Collapsing）

Hystrix/Resilience4j 提供请求合并，在短时间窗口内将多个相同类型请求合并为一次批量调用：

```java
// 批量查询商品，替代 N 次单个查询
@FeignClient(name = "product-service")
public interface ProductClient {

    // 单个查询（高并发时产生大量请求）
    @GetMapping("/products/{id}")
    ProductDTO getProduct(@PathVariable Long id);

    // 批量查询（性能大幅提升）
    @PostMapping("/products/batch")
    List<ProductDTO> getProductsBatch(@RequestBody List<Long> ids);
}

// 在 OrderService 中，收集订单的所有 productId，一次批量查询
public List<OrderDetailVO> getOrderDetails(List<Long> orderIds) {
    List<Order> orders = orderRepository.findAllById(orderIds);

    // 收集所有 productId，去重
    Set<Long> productIds = orders.stream()
        .map(Order::getProductId)
        .collect(Collectors.toSet());

    // 一次批量 Feign 调用，替代 N 次单个调用
    Map<Long, ProductDTO> productMap = productClient
        .getProductsBatch(new ArrayList<>(productIds))
        .stream()
        .collect(Collectors.toMap(ProductDTO::getId, p -> p));

    return orders.stream()
        .map(o -> buildVO(o, productMap.get(o.getProductId())))
        .toList();
}
```

---

## 7. 链路压测与性能基线

### 7.1 压测工具

```bash
# k6（推荐，现代压测工具）
k6 run --vus 100 --duration 60s script.js

# JMeter（功能丰富，GUI 友好）
jmeter -n -t test_plan.jmx -l result.jtl

# Gatling（Scala DSL，报告美观）
gatling.sh -s com.example.OrderSimulation
```

### 7.2 k6 压测脚本示例

```javascript
// load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },   // 逐渐增加到 50 并发
    { duration: '60s', target: 50 },   // 保持 50 并发 1 分钟
    { duration: '30s', target: 100 },  // 增加到 100 并发
    { duration: '60s', target: 100 },  // 保持 100 并发 1 分钟
    { duration: '30s', target: 0 },    // 降至 0
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],  // 95% 请求在 500ms 内
    http_req_failed: ['rate<0.01'],    // 失败率 < 1%
  },
};

export default function () {
  const res = http.get('http://api-gateway/api/products/1', {
    headers: { 'Authorization': `Bearer ${__ENV.TOKEN}` },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 200ms': (r) => r.timings.duration < 200,
  });

  sleep(0.1);
}
```

### 7.3 性能基线与告警

建立服务的性能基线，配置 Prometheus 告警规则：

```yaml
# prometheus/alerts.yml
groups:
  - name: performance
    rules:
      - alert: HighResponseTime
        expr: |
          histogram_quantile(0.95,
            rate(http_server_requests_seconds_bucket[5m])
          ) > 0.5
        for: 2m
        annotations:
          summary: "P95 响应时间超过 500ms"
          description: "服务 {{ $labels.service }} 的 P95 响应时间为 {{ $value }}s"

      - alert: LowThroughput
        expr: |
          sum(rate(http_server_requests_total[1m])) by (service) < 10
        for: 5m
        annotations:
          summary: "服务吞吐量异常低"
```

---

## 8. 性能优化清单

```
🔹 服务间通信
  ☑ Feign 启用连接池（OkHttp/Apache HttpClient）
  ☑ 串行调用改为并行（CompletableFuture）
  ☑ 批量接口替代循环单次调用
  ☑ 合理设置超时时间

🔹 缓存
  ☑ 热点数据使用多级缓存（Caffeine + Redis）
  ☑ 布隆过滤器防止缓存穿透
  ☑ 分布式锁防止缓存击穿
  ☑ 随机过期时间防止缓存雪崩
  ☑ 缓存命中率监控（< 90% 需调查）

🔹 数据库
  ☑ HikariCP 连接池参数调优
  ☑ 读写分离（读操作走从库）
  ☑ 关键查询添加索引（EXPLAIN 分析）
  ☑ 避免 SELECT * 查询
  ☑ 批量操作代替循环单条操作

🔹 JVM
  ☑ Spring Boot 3.2+ 开启虚拟线程
  ☑ 合理设置堆内存（容器场景用 MaxRAMPercentage）
  ☑ 开启 OOM 时堆转储
  ☑ 监控 GC 停顿时间

🔹 架构
  ☑ 耗时操作改为异步处理
  ☑ 建立性能基线，配置 RT/QPS 告警
  ☑ 定期压测验证性能指标
```

---

**上一篇：** [23 - 多租户架构](./23-multi-tenancy.md)
**下一篇：** [25 - 微服务治理](./25-service-governance.md)
