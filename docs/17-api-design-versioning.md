# 微服务系列 17 - API 设计与版本化

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么 API 设计很重要？

微服务之间通过 API 通信，API 就是服务的**契约**。好的 API 设计让服务易于理解、使用和演进；差的 API 设计则导致客户端频繁适配、服务难以变更。

```
API 是微服务的"门面"：

┌──────────────────────────────┐
│        Microservice          │
│                              │
│   ┌──────────────────────┐  │
│   │   API Contract        │◀── 外部世界只看得到这一层
│   │   (接口契约)          │  │
│   └──────────────────────┘  │
│   ┌──────────────────────┐  │
│   │   Business Logic     │  │
│   └──────────────────────┘  │
│   ┌──────────────────────┐  │
│   │   Data Layer          │  │
│   └──────────────────────┘  │
└──────────────────────────────┘
```

---

## 2. RESTful API 设计原则

### 2.1 资源命名

```
✅ 好的设计：
GET    /api/v1/users          → 获取用户列表
GET    /api/v1/users/123      → 获取单个用户
POST   /api/v1/users          → 创建用户
PUT    /api/v1/users/123      → 更新用户（全量）
PATCH  /api/v1/users/123      → 更新用户（部分）
DELETE /api/v1/users/123      → 删除用户

GET    /api/v1/users/123/orders → 获取用户 123 的订单

❌ 不好的设计：
GET    /api/v1/getUser?id=123     → 动词不该出现在 URL 中
POST   /api/v1/deleteUser         → 用 DELETE 方法
GET    /api/v1/user_list          → 蛇形命名不一致
POST   /api/v1/users/create       → POST 本身就是创建
```

### 2.2 命名规范速查

| 规则 | 说明 | 示例 |
|------|------|------|
| 用名词不用动词 | URL 表示资源 | `/users` not `/getUsers` |
| 用复数 | 集合资源 | `/users` not `/user` |
| 用小写+连字符 | 多词用 `-` | `/user-profiles` |
| 嵌套不超过两层 | 避免深层嵌套 | `/users/123/orders` |
| 查询用参数 | 过滤、排序、分页 | `/users?role=admin&page=1` |

### 2.3 HTTP 方法语义

| 方法 | 语义 | 幂等 | 安全 | 请求体 | 响应 |
|------|------|------|------|--------|------|
| **GET** | 获取资源 | ✅ | ✅ | 无 | 200 + 资源 |
| **POST** | 创建资源 | ❌ | ❌ | 资源数据 | 201 + Location |
| **PUT** | 全量更新 | ✅ | ❌ | 完整资源 | 200 + 资源 |
| **PATCH** | 部分更新 | ❌ | ❌ | 部分字段 | 200 + 资源 |
| **DELETE** | 删除资源 | ✅ | ❌ | 无 | 204 |

### 2.4 状态码使用

```
2xx 成功
├── 200 OK              → 查询、更新成功
├── 201 Created         → 创建成功
├── 202 Accepted        → 已接受，异步处理中
└── 204 No Content      → 删除成功，无返回体

4xx 客户端错误
├── 400 Bad Request      → 参数校验失败
├── 401 Unauthorized     → 未认证
├── 403 Forbidden        → 无权限
├── 404 Not Found        → 资源不存在
├── 409 Conflict         → 资源冲突（如重复创建）
└── 422 Unprocessable    → 语义错误

5xx 服务端错误
├── 500 Internal Error   → 服务端异常
├── 502 Bad Gateway      → 网关上游错误
├── 503 Unavailable      → 服务不可用
└── 504 Gateway Timeout  → 网关超时
```

---

## 3. 分页、排序与过滤

### 3.1 分页

```http
GET /api/v1/users?page=1&size=20
```

```java
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @GetMapping
    public PageResponse<UserResponse> getUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("id").ascending());
        Page<User> userPage = userService.getUsers(pageable);
        
        return PageResponse.<UserResponse>builder()
            .content(userPage.getContent().stream().map(this::toResponse).toList())
            .page(page)
            .size(size)
            .totalElements(userPage.getTotalElements())
            .totalPages(userPage.getTotalPages())
            .build();
    }
}
```

```java
@Data
@Builder
public class PageResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
}
```

### 3.2 排序与过滤

```http
# 排序
GET /api/v1/users?sort=name,asc&sort=createdAt,desc

# 过滤
GET /api/v1/users?role=admin&status=active

# 搜索
GET /api/v1/users?keyword=张

# 组合
GET /api/v1/users?role=admin&sort=name,asc&page=1&size=20
```

---

## 4. API 版本化

### 4.1 版本化策略对比

| 策略 | 示例 | 优点 | 缺点 |
|------|------|------|------|
| **URL 路径** | `/api/v1/users` | 简单直观 | URL 变更 |
| **请求头** | `Accept: application/vnd.myapi.v1+json` | URL 不变 | 不直观 |
| **查询参数** | `/api/users?version=1` | 简单 | 不规范 |
| **自定义头部** | `X-API-Version: 1` | URL 不变 | 不标准 |

**推荐：URL 路径版本化**（最直观、最常用）

### 4.2 Spring Boot 实现版本化

**方式一：URL 路径**

```java
// V1 Controller
@RestController
@RequestMapping("/api/v1/users")
public class UserV1Controller {

    @GetMapping("/{id}")
    public UserResponseV1 getUser(@PathVariable Long id) {
        // V1 版本返回格式
        return userService.getUserV1(id);
    }
}

// V2 Controller（新增了角色信息）
@RestController
@RequestMapping("/api/v2/users")
public class UserV2Controller {

    @GetMapping("/{id}")
    public UserResponseV2 getUser(@PathVariable Long id) {
        // V2 版本返回格式
        return userService.getUserV2(id);
    }
}
```

**方式二：自定义 Header**

```java
@Configuration
public class ApiVersionConfig {

    @Bean
    public WebMvcRegistrations webMvcRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new ApiVersionRequestMappingHandlerMapping("X-API-Version");
            }
        };
    }
}

// 使用
@GetMapping(value = "/api/users/{id}", headers = "X-API-Version=1")
public UserResponseV1 getUserV1(@PathVariable Long id) { ... }

@GetMapping(value = "/api/users/{id}", headers = "X-API-Version=2")
public UserResponseV2 getUserV2(@PathVariable Long id) { ... }
```

### 4.3 版本化最佳实践

```
1. 从 v1 开始，不要省略版本号
   ✅ /api/v1/users
   ❌ /api/users（没有版本号，后续升级困难）

2. v1 和 v2 共存至少 6 个月
   给客户端充足的迁移时间

3. 废弃版本提前通知
   响应头：Sunset: Sat, 01 Jan 2025 00:00:00 GMT
   响应头：Link: </api/v2/users>; rel="successor-version"

4. 优先添加字段，不删除字段
   ✅ V2 在 V1 基础上新增 role 字段 → V1 客户端忽略新字段
   ❌ V2 重命名 name 为 fullName → V1 客户端崩溃

5. 向后兼容的变更不需要新版本
   - 新增可选请求参数
   - 新增响应字段
   - 新增 API 端点
```

---

## 5. 契约测试（Consumer Driven Contract）

### 5.1 为什么需要契约测试？

```
问题：Provider API 变更导致 Consumer 出错

Provider (Order Service)        Consumer (User Service)
┌──────────────────┐           ┌──────────────────┐
│ 返回格式变更：     │           │ 期望旧格式：      │
│ { "orderId": 1 } │───???───▶│ { "id": 1 }      │
└──────────────────┘           └──────────────────┘

解决：Consumer 定义契约，Provider 验证不破坏契约
```

### 5.2 Spring Cloud Contract

**Consumer 端定义契约：**

```groovy
// contracts/order-service/getOrder.groovy
Contract.make {
    description "Should return order by ID"
    request {
        method GET()
        url "/api/v1/orders/1"
    }
    response {
        status 200
        headers {
            contentType(applicationJson())
        }
        body([
            id      : 1,
            userId  : 1,
            productId: 5,
            quantity: 2,
            status  : "CREATED"
        ])
    }
}
```

**Provider 端验证：**

```java
@AutoConfigureStubRunner(
    ids = "com.example:order-service",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL
)
@SpringBootTest
class OrderClientContractTest {

    @Autowired
    private OrderClient orderClient;

    @Test
    void shouldReturnOrderById() {
        OrderDTO order = orderClient.getOrderById(1L);
        assertThat(order.getId()).isEqualTo(1L);
        assertThat(order.getStatus()).isEqualTo("CREATED");
    }
}
```

---

## 6. API 文档（OpenAPI / Swagger）

### 6.1 SpringDoc 配置

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.0</version>
</dependency>
```

### 6.2 注解示例

```java
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User API", description = "用户管理接口")
public class UserController {

    @Operation(summary = "获取用户详情", description = "根据用户 ID 获取用户信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "成功"),
        @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    @GetMapping("/{id}")
    public UserResponse getUserById(
            @Parameter(description = "用户 ID", required = true)
            @PathVariable Long id) {
        return userService.getUserById(id);
    }

    @Operation(summary = "创建用户")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public UserResponse createUser(
            @Parameter(description = "用户信息", required = true)
            @Valid @RequestBody UserRequest request) {
        return userService.createUser(request);
    }
}
```

访问：`http://localhost:8081/swagger-ui.html`

---

## 7. API 设计检查清单

| 检查项 | 说明 |
|--------|------|
| ✅ 资源用复数名词 | `/users` not `/user` |
| ✅ URL 中不用动词 | `POST /users` not `POST /createUser` |
| ✅ 正确使用 HTTP 方法 | GET/POST/PUT/PATCH/DELETE 语义正确 |
| ✅ 正确使用状态码 | 不该全返回 200 |
| ✅ API 有版本号 | `/api/v1/users` |
| ✅ 分页支持 | 大列表必须分页 |
| ✅ 错误响应统一 | `{"code": 400, "message": "..."}` |
| ✅ API 文档 | OpenAPI/Swagger |
| ✅ 契约测试 | Consumer Driven Contract |
| ✅ 向后兼容 | 新增字段而非重命名 |
| ✅ 幂等设计 | PUT/DELETE 必须幂等 |

---

**上一篇：** [16 - 事件驱动架构](./16-event-driven-architecture.md)  
**下一篇：** [18 - 微服务测试策略](./18-testing-microservices.md)
