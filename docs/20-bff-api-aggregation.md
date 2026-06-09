# 微服务系列 20 - BFF 模式与 API 聚合

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 什么是 BFF？

BFF（Backend for Frontend）是一种为特定前端类型定制的后端服务模式。不同客户端（Web、Mobile、IoT）有不同的数据需求，BFF 为每种客户端提供专用的 API。

```
没有 BFF：
┌────────┐ ┌────────┐ ┌────────┐
│  Web   │ │ Mobile │ │  IoT   │
└───┬────┘ └───┬────┘ └───┬────┘
    │          │          │
    └──────────┼──────────┘
               │
        ┌──────▼──────┐
        │ API Gateway │ ← 所有客户端共享同一 API
        └──────┬──────┘    移动端拿到太多不需要的数据
               │           Web 端需要多次请求拼装页面

有 BFF：
┌────────┐ ┌────────┐ ┌────────┐
│  Web   │ │ Mobile │ │  IoT   │
└───┬────┘ └───┬────┘ └───┬────┘
    │          │          │
┌───▼────┐┌───▼────┐┌───▼────┐
│ Web    ││ Mobile ││  IoT   │
│  BFF   ││  BFF   ││  BFF   │ ← 每种客户端一个 BFF
└───┬────┘└───┬────┘└───┬────┘    定制化的 API 聚合
    └──────────┼──────────┘
        ┌──────▼──────┐
        │ Microservices│
        └──────────────┘
```

### 1.1 BFF 的核心价值

| 价值 | 说明 |
|------|------|
| **按需裁剪** | 移动端只返回必要字段，减少流量 |
| **聚合调用** | 一次请求聚合多个微服务数据 |
| **协议适配** | 外部 REST → 内部 gRPC |
| **客户端优化** | 为不同设备做缓存、数据格式转换 |
| **隔离变化** | 微服务 API 变更不影响客户端 |

---

## 2. API 聚合模式

### 2.1 常见聚合场景

```
场景 1：首页聚合
Web 首页需要：用户信息 + 推荐商品 + 最近订单 + 通知数

没有聚合：4 次 HTTP 请求
Web → GET /users/1
Web → GET /products/recommend
Web → GET /orders/recent
Web → GET /notifications/count

有 BFF 聚合：1 次 HTTP 请求
Web → GET /api/home
BFF 并行调用 4 个服务 → 组装响应 → 返回
```

### 2.2 聚合策略

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| **串行聚合** | 依次调用，有依赖关系 | 后续调用依赖前一个结果 |
| **并行聚合** | 同时调用，等全部完成 | 调用之间无依赖 |
| **流式聚合** | 部分结果先返回 | 大数据量、实时场景 |

---

## 3. 实战：Spring Cloud 实现 BFF

### 3.1 并行聚合

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class HomeAggregationService {

    private final UserClient userClient;
    private final ProductClient productClient;
    private final OrderClient orderClient;

    /**
     * 并行调用多个服务，聚合首页数据
     */
    public HomeResponse getHomeData(Long userId) {
        CompletableFuture<UserDTO> userFuture = CompletableFuture.supplyAsync(
            () -> userClient.getUserById(userId));

        CompletableFuture<List<ProductDTO>> recommendFuture = CompletableFuture.supplyAsync(
            () -> productClient.getRecommendations());

        CompletableFuture<List<OrderDTO>> recentOrdersFuture = CompletableFuture.supplyAsync(
            () -> orderClient.getRecentOrders(userId));

        // 等待所有调用完成
        try {
            CompletableFuture.allOf(userFuture, recommendFuture, recentOrdersFuture)
                .get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Home data aggregation timeout for userId={}", userId);
            throw new BusinessException(504, "首页数据加载超时");
        } catch (Exception e) {
            throw new BusinessException(500, "首页数据加载失败");
        }

        return HomeResponse.builder()
            .user(userFuture.join())
            .recommendations(recommendFuture.join())
            .recentOrders(recentOrdersFuture.join())
            .build();
    }
}
```

### 3.2 降级聚合

```java
@Service
@Slf4j
public class ResilientHomeAggregationService {

    @Autowired private UserClient userClient;
    @Autowired private ProductClient productClient;
    @Autowired private OrderClient orderClient;

    public HomeResponse getHomeData(Long userId) {
        HomeResponse.HomeResponseBuilder builder = HomeResponse.builder();

        // 用户信息 - 必须成功
        try {
            builder.user(userClient.getUserById(userId));
        } catch (Exception e) {
            log.warn("Failed to fetch user: {}", e.getMessage());
            throw new BusinessException(503, "用户服务不可用");
        }

        // 推荐商品 - 可降级
        try {
            builder.recommendations(productClient.getRecommendations());
        } catch (Exception e) {
            log.warn("Failed to fetch recommendations, using fallback: {}", e.getMessage());
            builder.recommendations(Collections.emptyList());
            builder.recommendationsAvailable(false);
        }

        // 最近订单 - 可降级
        try {
            builder.recentOrders(orderClient.getRecentOrders(userId));
        } catch (Exception e) {
            log.warn("Failed to fetch recent orders, using fallback: {}", e.getMessage());
            builder.recentOrders(Collections.emptyList());
            builder.ordersAvailable(false);
        }

        return builder.build();
    }
}
```

### 3.3 BFF Controller

```java
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeBffController {

    private final ResilientHomeAggregationService homeService;

    @GetMapping
    public HomeResponse getHome(@RequestHeader("X-User-Id") Long userId) {
        return homeService.getHomeData(userId);
    }
}

@Data
@Builder
public class HomeResponse {
    private UserDTO user;
    private List<ProductDTO> recommendations;
    private List<OrderDTO> recentOrders;
    private boolean recommendationsAvailable;
    private boolean ordersAvailable;
}
```

---

## 4. GraphQL 作为 BFF

GraphQL 天然适合 BFF 场景——客户端按需查询字段。

### 4.1 依赖

```xml
<dependency>
    <groupId>com.graphql-java</groupId>
    <artifactId>graphql-java</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-graphql</artifactId>
</dependency>
```

### 4.2 Schema 定义

```graphql
# schema.graphqls
type Query {
    user(id: ID!): User
    home(userId: ID!): HomeData
}

type User {
    id: ID!
    username: String!
    name: String!
    email: String
    phone: String
}

type Product {
    id: ID!
    name: String!
    price: Float!
    stock: Int
}

type Order {
    id: ID!
    productId: ID!
    productName: String
    quantity: Int!
    totalPrice: Float!
    status: String!
}

type HomeData {
    user: User!
    recommendations: [Product!]!
    recentOrders: [Order!]!
}
```

### 4.3 DataLoader（批量查询 + 缓存）

```java
@Component
@RequiredArgsConstructor
public class UserDataLoader extends BatchLoader<Long, UserDTO> {

    private final UserClient userClient;

    @Override
    public CompletionStage<List<UserDTO>> load(List<Long> userIds) {
        return CompletableFuture.supplyAsync(() ->
            userIds.stream()
                .map(id -> {
                    try {
                        return userClient.getUserById(id);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .toList()
        );
    }
}
```

### 4.4 GraphQL vs REST BFF

| 维度 | REST BFF | GraphQL BFF |
|------|----------|-------------|
| **灵活性** | 固定响应结构 | 客户端按需查询 |
| **过度获取** | 可能 | 不会 |
| **获取不足** | 需多次请求 | 一次查询搞定 |
| **学习曲线** | 低 | 中 |
| **缓存** | HTTP 缓存 | 需要额外处理 |
| **版本化** | 需要 | 不需要（按需查询） |

---

## 5. BFF 反模式

### ❌ 反模式 1：BFF 包含业务逻辑

BFF 只做聚合和适配，不包含业务规则。业务逻辑应在微服务中。

### ❌ 反模式 2：BFF 过多

不是每个页面都需要 BFF，共享 BFF 可以减少维护成本：

```
❌ 过多 BFF：
HomePageBFF, ProfileBFF, OrderPageBFF, SettingsBFF...

✅ 合理的 BFF：
WebBFF (服务所有 Web 页面), MobileBFF (服务所有 Mobile 页面)
```

### ❌ 反模式 3：BFF 间共享数据库

BFF 不应该有自己的数据库，它只是微服务数据的聚合层。

---

## 6. BFF 最佳实践

1. **按客户端类型划分 BFF**：Web、Mobile、OpenAPI
2. **并行调用 + 降级**：用 CompletableFuture 并行，失败时降级
3. **超时控制**：BFF 必须设全局超时
4. **缓存热点数据**：推荐、配置等不常变的数据可缓存
5. **限流**：BFF 是入口，需要限流
6. **监控**：聚合调用的每一步都要有监控
7. **BFF 无状态**：不持有数据，方便水平扩展

---

**上一篇：** [19 - 数据库模式](./19-database-patterns.md)  
**下一篇：** [21 - 云原生设计模式](./21-cloud-native-patterns.md)