# 微服务系列 18 - 微服务测试策略

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 微服务测试的挑战

单体应用中，所有代码在同一个进程中，测试相对简单。微服务中，一个功能可能跨越多个服务，测试复杂度显著增加：

```
单体测试：
┌─────────────────────────────────┐
│          单元测试 + 集成测试      │
│         全部在一个进程中完成       │
└─────────────────────────────────┘

微服务测试：
┌──────────┐   ┌──────────┐   ┌──────────┐
│User Svc  │──▶│Order Svc │──▶│Product Svc│
│  测试？   │   │  测试？   │   │  测试？   │
└──────────┘   └──────────┘   └──────────┘
     ↑              ↑              ↑
   需要独立测试   需要测试集成    需要端到端验证
```

### 1.1 核心挑战

| 挑战 | 说明 |
|------|------|
| **服务依赖** | 测试一个服务需要其他服务配合 |
| **环境管理** | 测试环境搭建复杂 |
| **数据准备** | 每个服务有独立数据库 |
| **网络不确定性** | 网络延迟、故障难以模拟 |
| **测试速度** | 端到端测试慢，反馈周期长 |

---

## 2. 测试金字塔

### 2.1 经典测试金字塔

```
                ┌─────────┐
                │  E2E    │  少量：端到端测试
                │  Tests  │  速度慢、成本高、不稳定
               ┌┴─────────┴┐
               │ Integration│  适量：集成测试
               │   Tests    │  验证服务间交互
              ┌┴────────────┴┐
              │   Component  │  适量：组件测试
              │     Tests    │  隔离测试单个服务
             ┌┴──────────────┴┐
             │   Unit Tests   │  大量：单元测试
             │                │  速度快、稳定
             └────────────────┘
```

### 2.2 微服务测试象限

更精确的分类法，兼顾业务/技术维度和反馈速度：

```
                     支持开发                  批评产品
                ┌────────────────┬────────────────┐
   面向技术    │   单元测试       │   性能测试      │
   (实现)      │   集成测试       │   安全测试      │
               │  (快速反馈)     │  (非功能性验证)  │
               ├────────────────┼────────────────┤
   面向业务    │   功能测试       │   验收测试      │
   (行为)      │   契约测试      │   探索性测试    │
               │  (行为验证)     │  (用户视角)     │
               └────────────────┴────────────────┘
```

---

## 3. 单元测试

### 3.1 原则

- 测试单个类/方法的行为
- 不依赖外部资源（数据库、网络）
- 速度快（毫秒级）
- 使用 Mock 隔离依赖

### 3.2 示例

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("创建用户 - 用户名已存在时抛出异常")
    void createUser_usernameExists_throwsException() {
        // Arrange
        UserRequest request = new UserRequest();
        request.setUsername("zhangsan");
        request.setPassword("password123");
        request.setName("张三");

        when(userRepository.existsByUsername("zhangsan")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> userService.createUser(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Username already exists");

        verify(userRepository).existsByUsername("zhangsan");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("创建用户 - 正常情况返回用户信息")
    void createUser_success_returnsUser() {
        // Arrange
        UserRequest request = new UserRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setName("新用户");

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        // Act
        UserResponse response = userService.createUser(request);

        // Assert
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getUsername()).isEqualTo("newuser");
        assertThat(response.getName()).isEqualTo("新用户");

        verify(userRepository).save(argThat(user ->
            user.getUsername().equals("newuser") &&
            user.getName().equals("新用户")
        ));
    }

    @Test
    @DisplayName("获取用户 - 用户不存在时抛出异常")
    void getUserById_notFound_throwsException() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(999L))
            .isInstanceOf(BusinessException.class)
            .extracting("code")
            .isEqualTo(404);
    }
}
```

---

## 4. 集成测试

### 4.1 Spring Boot 集成测试

测试 Spring 上下文中组件的协作：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class UserControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /users - 创建用户并返回 201")
    void createUser_returnsCreated() {
        // Arrange
        UserRequest request = new UserRequest();
        request.setUsername("testuser");
        request.setPassword("password123");
        request.setName("Test User");

        // Act
        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(
            "/users", request, ApiResponse.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getCode()).isEqualTo(200);

        // 验证数据库
        assertThat(userRepository.findByUsername("testuser")).isPresent();
    }

    @Test
    @DisplayName("GET /users/{id} - 返回用户详情")
    void getUserById_returnsUser() {
        // Arrange
        User user = User.builder()
            .username("existing")
            .password("password")
            .name("Existing User")
            .build();
        user = userRepository.save(user);

        // Act
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/users/" + user.getId(), String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("existing");
    }
}
```

### 4.2 Testcontainers（真实数据库测试）

```java
@SpringBootTest
@Testcontainers
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void findByUsername_existingUser_returnsUser() {
        // 使用真实 PostgreSQL 测试
        User user = User.builder()
            .username("dbuser")
            .password("password")
            .name("DB User")
            .build();
        userRepository.save(user);

        Optional<User> found = userRepository.findByUsername("dbuser");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("DB User");
    }
}
```

---

## 5. 契约测试

### 5.1 为什么契约测试至关重要？

```
没有契约测试：
Provider 变更 API → Consumer 线上故障 → 甩锅 😱

有契约测试：
Provider 变更 API → CI 契约测试失败 → 提前发现 🎉
```

### 5.2 Pact 契约测试

**Consumer 端定义契约：**

```java
@PactTestFor(providerName = "user-service")
@Test
void verifyUserContract(MockServer mockServer) {
    // 1. 设置期望
    UserResponse expectedUser = UserResponse.builder()
        .id(1L)
        .username("zhangsan")
        .name("张三")
        .build();

    // 2. 创建 Pact
    Map<String, Object> response = new HashMap<>();
    response.put("id", 1L);
    response.put("username", "zhangsan");
    response.put("name", "张三");

    // 3. 验证 Consumer 能正确消费
    UserClient client = new UserClient(mockServer.getUrl());
    UserResponse actual = client.getUserById(1L);
    assertThat(actual).isEqualTo(expectedUser);
}

@Pact(consumer = "order-service", provider = "user-service")
public RequestResponsePact getUserPact(PactDslWithProvider builder) {
    return builder
        .given("user with id 1 exists")
        .uponReceiving("a request to get user by id")
        .path("/users/1")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(Map.of("Content-Type", "application/json"))
        .body(new PactDslJsonBody()
            .numberType("id", 1L)
            .stringType("username", "zhangsan")
            .stringType("name", "张三"))
        .toPact();
}
```

**Provider 端验证契约：**

```java
@PactBroker(url = "http://localhost:9292")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserProviderPactTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @BeforeEach
    void setup(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @State("user with id 1 exists")
    void userExists() {
        // 准备测试数据
        userRepository.save(User.builder()
            .id(1L).username("zhangsan").name("张三").build());
    }
}
```

---

## 6. 端到端测试

### 6.1 原则

- 只覆盖核心业务场景（1-5%）
- 使用真实环境（或尽可能接近）
- 每次部署后运行

### 6.2 示例

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("完整下单流程：创建用户→创建商品→下单→查看订单详情")
    void completeOrderFlow() {
        // Step 1: 创建用户
        UserRequest userReq = new UserRequest();
        userReq.setUsername("e2euser");
        userReq.setPassword("password123");
        userReq.setName("E2E User");

        ResponseEntity<Long> userResp = restTemplate.postForEntity(
            "http://localhost:8081/users", userReq, Long.class);
        Long userId = extractId(userResp);
        assertThat(userId).isNotNull();

        // Step 2: 创建商品
        ProductRequest productReq = new ProductRequest();
        productReq.setName("E2E Product");
        productReq.setPrice(new BigDecimal("99.99"));
        productReq.setStock(100);

        ResponseEntity<Long> productResp = restTemplate.postForEntity(
            "http://localhost:8083/products", productReq, Long.class);
        Long productId = extractId(productResp);
        assertThat(productId).isNotNull();

        // Step 3: 创建订单
        OrderRequest orderReq = new OrderRequest();
        orderReq.setUserId(userId);
        orderReq.setProductId(productId);
        orderReq.setQuantity(2);

        ResponseEntity<Long> orderResp = restTemplate.postForEntity(
            "http://localhost:8082/orders", orderReq, Long.class);
        Long orderId = extractId(orderResp);
        assertThat(orderId).isNotNull();

        // Step 4: 查看订单详情
        ResponseEntity<String> detailResp = restTemplate.getForEntity(
            "http://localhost:8082/orders/" + orderId + "/detail", String.class);
        assertThat(detailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResp.getBody()).contains("E2E User");
        assertThat(detailResp.getBody()).contains("E2E Product");
    }
}
```

---

## 7. 混沌测试

### 7.1 Chaos Monkey

给 Spring Boot 应用注入故障：

```xml
<dependency>
    <groupId>de.codecentric</groupId>
    <artifactId>chaos-monkey-spring-boot</artifactId>
    <version>3.1.0</version>
</dependency>
```

```yaml
chaos:
  monkey:
    enabled: true
    watcher:
      controller: true
      service: true
      repository: true
    assaults:
      level: 3
      latency-active: true
      latency-range-start: 1000
      latency-range-end: 5000
      exceptions-active: true
      kill-application-active: false
```

### 7.2 测试弹性

```java
@SpringBootTest
@ActiveProfiles("chaos")  // 启用 Chaos Monkey
class OrderServiceResilienceTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Product Service 延迟时订单服务应降级")
    void createOrder_productServiceSlow_shouldDegrade() {
        OrderRequest request = new OrderRequest();
        request.setUserId(1L);
        request.setProductId(1L);
        request.setQuantity(1);

        // 即使 Product Service 延迟，Order Service 也不应该崩溃
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/orders", request, String.class);

        // 应该返回降级响应或超时错误，而不是 500
        assertThat(response.getStatusCode().value()).isIn(200, 503);
    }
}
```

---

## 8. 测试策略总结

| 测试类型 | 占比 | 速度 | 范围 | 运行时机 |
|----------|------|------|------|----------|
| **单元测试** | 70% | 毫秒 | 单个类/方法 | 每次提交 |
| **集成测试** | 15% | 秒 | 单个服务 | 每次提交 |
| **契约测试** | 5% | 秒 | 服务间契约 | 每次提交 |
| **端到端测试** | 5% | 分钟 | 完整链路 | 部署前 |
| **混沌测试** | 5% | 分钟 | 弹性验证 | 定期运行 |

### 测试最佳实践

1. **优先写单元测试**：速度快、稳定、覆盖率高
2. **集成测试用 Testcontainers**：真实数据库，不依赖环境
3. **契约测试不可少**：保证服务间 API 兼容
4. **E2E 测试只覆盖核心链路**：如"下单-支付-发货"
5. **CI 中运行单元+集成+契约**：快速反馈
6. **定期运行混沌测试**：验证系统韧性
7. **测试数据独立**：每个测试准备和清理自己的数据

---

**上一篇：** [17 - API 设计与版本化](./17-api-design-versioning.md)  
**下一篇：** [19 - 数据库模式](./19-database-patterns.md)