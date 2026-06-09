# 微服务系列 23 - 多租户架构

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 什么是多租户？

多租户（Multi-Tenancy）是指一套软件系统同时服务于多个客户（租户），每个租户的数据彼此隔离、互不可见，但共享同一套代码和基础设施。

```
单租户（独立部署）：
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  公司 A 系统  │  │  公司 B 系统  │  │  公司 C 系统  │
│  独立代码     │  │  独立代码     │  │  独立代码     │
│  独立数据库   │  │  独立数据库   │  │  独立数据库   │
└──────────────┘  └──────────────┘  └──────────────┘
成本高，维护难

多租户（共享部署）：
┌──────────────────────────────────────┐
│           统一系统                     │
│   ┌──────┐  ┌──────┐  ┌──────┐       │
│   │租户A  │  │租户B  │  │租户C  │       │
│   │数据   │  │数据   │  │数据   │       │
│   └──────┘  └──────┘  └──────┘       │
│   共享代码、共享基础设施               │
└──────────────────────────────────────┘
成本低，维护简单，数据隔离是关键
```

### 1.1 典型应用场景

- **SaaS 平台**：同一套代码服务于不同企业客户（如 CRM、ERP、电商 SaaS）
- **B2B2C 平台**：平台方为多个商家提供相同能力（如多商户电商平台）
- **内部多部门系统**：不同业务部门共享技术平台

---

## 2. 多租户隔离模型

### 2.1 三种隔离模型对比

```
隔离级别（从高到低）：

★★★ 独立数据库（Database per Tenant）
每个租户有独立数据库实例
┌─────────────────────────────────────┐
│  Order Service                       │
│  ┌────────────────────────────────┐  │
│  │  租户路由层                     │  │
│  └──┬─────────────┬──────────────┘  │
│     ▼             ▼                  │
│  [DB-TenantA]  [DB-TenantB]         │
└─────────────────────────────────────┘
优点：最高隔离性，数据安全，性能不互相影响
缺点：数据库数量多，维护成本高

★★☆ 独立 Schema（Schema per Tenant）
同一数据库实例，不同 Schema
┌─────────────────────────────────────┐
│  Order Service                       │
│  同一数据库实例                       │
│  ├── schema_tenant_a.orders          │
│  ├── schema_tenant_b.orders          │
│  └── schema_tenant_c.orders          │
└─────────────────────────────────────┘
优点：隔离性较好，数据库实例少
缺点：Schema 太多时管理困难

★☆☆ 共享表（Shared Table）
所有租户数据在同一张表，通过 tenant_id 区分
┌─────────────────────────────────────┐
│  orders 表                           │
│  id | tenant_id | user_id | ...      │
│  1  | tenant_a  | 100     | ...      │
│  2  | tenant_b  | 200     | ...      │
└─────────────────────────────────────┘
优点：实现简单，资源利用率高
缺点：数据隔离靠代码保证，风险较高
```

### 2.2 如何选择？

| 因素 | 独立数据库 | 独立 Schema | 共享表 |
|------|-----------|------------|--------|
| **数据隔离要求** | 极高（金融/医疗） | 高 | 一般 |
| **租户数量** | < 100 | 100~1000 | > 1000 |
| **定制化需求** | 高 | 中 | 低 |
| **运维复杂度** | 高 | 中 | 低 |
| **成本** | 高 | 中 | 低 |

---

## 3. 租户标识传递

### 3.1 租户 ID 的来源

```
客户端请求 → API Gateway → 各微服务

租户 ID 可以来自：
① URL 路径：  /api/tenants/{tenantId}/orders
② 子域名：    tenant-a.example.com  →  tenantId = "tenant-a"
③ JWT 声明：  token.claims["tenantId"] = "tenant-a"
④ 请求头：    X-Tenant-ID: tenant-a
```

最常见的方案是**在 JWT token 中携带 tenantId**，由 API Gateway 解析后以请求头形式传递给下游服务。

### 3.2 API Gateway 提取租户

在上一章实现的 [JwtAuthFilter](../api-gateway/src/main/java/com/example/gateway/filter/JwtAuthFilter.java) 基础上扩展：

```java
// JwtAuthFilter.java 扩展
String tenantId = jwtUtil.getClaim(token, "tenantId");

ServerHttpRequest mutatedRequest = request.mutate()
    .header("X-User-Name", username)
    .header("X-User-Role", role != null ? role : "")
    .header("X-Tenant-ID", tenantId != null ? tenantId : "default")  // 新增
    .build();
```

### 3.3 TenantContext：线程本地存储

```java
/**
 * 租户上下文，使用 ThreadLocal 在当前请求线程中传递租户信息
 */
public class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();  // 防止内存泄漏！
    }
}
```

### 3.4 拦截器自动设置租户上下文

```java
/**
 * Spring MVC 拦截器：从请求头中提取 tenantId，设置到 TenantContext
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) {
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            // 可以返回 400，或使用默认租户
            tenantId = "default";
        }
        TenantContext.setTenantId(tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler, Exception ex) {
        TenantContext.clear();  // 请求结束后必须清理！
    }
}

// 注册拦截器
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/**");
    }
}
```

---

## 4. 数据隔离实现：共享表方案

共享表方案最为常用，核心是**在所有查询中自动追加 `tenant_id = ?` 条件**，避免数据越权。

### 4.1 MyBatis-Plus 多租户插件

MyBatis-Plus 提供了开箱即用的多租户插件：

```java
/**
 * MyBatis-Plus 配置：启用多租户拦截器
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 多租户插件（必须放在分页插件之前）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

            /**
             * 返回当前租户 ID（从 TenantContext 中获取）
             */
            @Override
            public Expression getTenantId() {
                String tenantId = TenantContext.getTenantId();
                return new StringValue(tenantId != null ? tenantId : "default");
            }

            /**
             * 返回存储租户 ID 的列名
             */
            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            /**
             * 哪些表不需要多租户过滤（公共表）
             */
            @Override
            public boolean ignoreTable(String tableName) {
                return List.of("sys_config", "sys_dict", "sys_region")
                           .contains(tableName);
            }
        }));

        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
```

### 4.2 实体类添加 tenant_id 字段

```java
@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 租户字段，MyBatis-Plus 自动处理，无需手动赋值
    private String tenantId;

    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal totalPrice;
    private String status;
    private LocalDateTime createdAt;
}
```

配置完成后，以下 SQL 会被 **自动改写**：

```sql
-- 原始查询
SELECT * FROM orders WHERE user_id = 100

-- 自动改写为（多租户安全）
SELECT * FROM orders WHERE user_id = 100 AND tenant_id = 'tenant_a'
```

### 4.3 忽略租户过滤（管理员场景）

```java
// 使用注解忽略，适合特定方法
@InterceptorIgnore(tenantLine = "true")
public List<Order> getAllTenantsOrders() {
    return orderMapper.selectList(null);
}

// 使用编程方式忽略，适合动态控制
try {
    InterceptorIgnoreHelper.handle(IgnoreStrategy.builder().tenantLine(true).build());
    return orderMapper.selectList(null);
} finally {
    InterceptorIgnoreHelper.clearIgnoreStrategy();
}
```

---

## 5. 数据隔离实现：独立 Schema 方案

### 5.1 动态数据源路由

```java
/**
 * 多租户动态数据源：根据当前租户路由到对应 Schema
 */
public class TenantDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        // 返回当前租户 ID 作为数据源 key
        return TenantContext.getTenantId();
    }
}
```

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource() {
        TenantDataSource routingDataSource = new TenantDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();

        // 为每个租户配置数据源
        targetDataSources.put("tenant_a", buildDataSource("tenant_a"));
        targetDataSources.put("tenant_b", buildDataSource("tenant_b"));
        targetDataSources.put("default", buildDataSource("default"));

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(
            targetDataSources.get("default")
        );

        return routingDataSource;
    }

    private DataSource buildDataSource(String schema) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://localhost:3306/" + schema);
        ds.setUsername("root");
        ds.setPassword("password");
        return ds;
    }
}
```

### 5.2 动态添加租户数据源

生产环境中，租户数量动态变化，需要运行时添加数据源：

```java
@Service
public class TenantDataSourceManager {

    @Autowired
    private TenantDataSource routingDataSource;

    /**
     * 新建租户时，动态注册数据源并初始化 Schema
     */
    @Transactional
    public void registerTenant(String tenantId, TenantConfig config) {
        // 1. 创建数据库 Schema
        createSchema(tenantId);

        // 2. 执行 DDL 初始化表结构
        runMigration(tenantId);

        // 3. 动态注册到路由数据源
        DataSource ds = buildDataSource(tenantId, config);
        routingDataSource.addTargetDataSource(tenantId, ds);

        log.info("Tenant registered: {}", tenantId);
    }

    private void createSchema(String tenantId) {
        // 使用 root 数据源执行
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + tenantId);
    }
}
```

---

## 6. Flyway 多租户数据库迁移

管理多租户 Schema 的表结构变更是一大挑战，Flyway 可以帮助自动化：

```java
@Component
public class MultiTenantMigrationRunner {

    @Autowired
    private TenantRegistry tenantRegistry;

    /**
     * 服务启动时，对所有租户的 Schema 执行迁移
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateAllTenants() {
        tenantRegistry.getAllTenants().forEach(tenant -> {
            Flyway flyway = Flyway.configure()
                .dataSource(buildDataSource(tenant))
                .locations("classpath:db/migration/tenant")
                .table("flyway_schema_history")
                .baselineOnMigrate(true)
                .load();
            flyway.migrate();
            log.info("Migration completed for tenant: {}", tenant.getId());
        });
    }
}
```

迁移脚本存放目录：

```
src/main/resources/db/migration/
├── common/          # 公共表（所有租户共享）
│   └── V1__create_config.sql
└── tenant/          # 租户专属表
    ├── V1__create_orders.sql
    ├── V2__add_order_index.sql
    └── V3__add_status_column.sql
```

---

## 7. 异步场景中的租户传递

异步消息（RabbitMQ/Kafka）需要显式传递租户 ID，否则消费者无法知道是哪个租户的消息。

### 7.1 消息发布时携带租户信息

```java
@Service
public class OrderEventPublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void publishOrderCreated(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
            .orderId(order.getId())
            .tenantId(TenantContext.getTenantId())  // 从上下文取出
            .userId(order.getUserId())
            .build();

        rabbitTemplate.convertAndSend(
            "order.exchange",
            "order.created",
            event
        );
    }
}
```

### 7.2 消息消费时恢复租户上下文

```java
@RabbitListener(queues = "order.created.queue")
public void handleOrderCreated(OrderCreatedEvent event) {
    // 在消费者线程中恢复租户上下文
    TenantContext.setTenantId(event.getTenantId());
    try {
        // 处理业务逻辑，此时 MyBatis-Plus 多租户插件能正确过滤数据
        inventoryService.deductStock(event);
    } finally {
        TenantContext.clear();  // 务必清理
    }
}
```

---

## 8. 租户级别的配置与功能开关

不同租户可能有不同的功能配置（Feature Flag）：

```java
@Service
public class TenantFeatureService {

    @Autowired
    private TenantConfigRepository configRepo;

    // 缓存租户配置，避免每次查库
    private final LoadingCache<String, TenantConfig> cache = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build(tenantId -> configRepo.findByTenantId(tenantId));

    public boolean isFeatureEnabled(String feature) {
        String tenantId = TenantContext.getTenantId();
        TenantConfig config = cache.get(tenantId);
        return config != null && config.isEnabled(feature);
    }
}
```

```java
// 使用示例：某租户开启了高级报表功能
@GetMapping("/reports/advanced")
public ReportData getAdvancedReport() {
    if (!tenantFeatureService.isFeatureEnabled("ADVANCED_REPORT")) {
        throw new BusinessException(403, "当前套餐不支持高级报表功能");
    }
    return reportService.generateAdvancedReport();
}
```

---

## 9. 完整请求链路

```
① 客户端请求（携带 JWT Token）
   Authorization: Bearer eyJhbGc...（含 tenantId 声明）

② API Gateway
   JwtAuthFilter 解析 token → 提取 tenantId
   添加请求头：X-Tenant-ID: tenant_a

③ 微服务（Spring MVC 拦截器）
   TenantInterceptor.preHandle() → TenantContext.setTenantId("tenant_a")

④ 业务代码调用数据库
   orderMapper.selectList(...)
       ↓ MyBatis-Plus 多租户插件自动改写 SQL
   SELECT * FROM orders WHERE ... AND tenant_id = 'tenant_a'

⑤ 请求结束
   TenantInterceptor.afterCompletion() → TenantContext.clear()
```

---

## 10. 常见问题

| 问题 | 解决方案 |
|------|----------|
| 异步线程丢失租户上下文 | 使用 `InheritableThreadLocal` 或封装线程池，手动传递 |
| 定时任务需要跑多个租户 | 遍历租户列表，逐一设置上下文后执行 |
| 数据越权（忘记 where tenant_id）| 使用 MyBatis-Plus 多租户插件，自动添加 |
| 新增租户时初始化数据 | Flyway 迁移脚本 + 租户注册事件监听器 |
| 超级管理员查看所有租户数据 | `@InterceptorIgnore(tenantLine = "true")` 或 RBAC 权限控制 |

---

**上一篇：** [22 - 生产就绪检查清单](./22-production-readiness.md)
**下一篇：** [24 - 微服务性能优化](./24-performance-optimization.md)
