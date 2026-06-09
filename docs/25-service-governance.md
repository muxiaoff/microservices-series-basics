# 微服务系列 25 - 微服务治理

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 什么是微服务治理？

随着微服务数量增长，系统的复杂度呈指数级上升：

```
5 个服务 → 还能靠人工管理
50 个服务 → 开始力不从心
500 个服务 → 失控，线上故障频发
```

微服务治理（Service Governance）是指通过**一套标准化的机制和平台**，对微服务的全生命周期进行统一管理：

```
微服务治理关注的核心问题：

服务注册与发现  → "我有哪些服务？它们在哪里？"
流量管理        → "请求该怎么走？流量该如何分配？"
配置管理        → "服务的行为由谁控制？如何热更新？"
熔断降级        → "依赖出问题了，怎么保护自己？"
限流控制        → "流量过大，怎么保护系统不被打垮？"
服务可观测性    → "系统现在健不健康？出了什么问题？"
权限与安全      → "谁能访问哪些服务？"
版本与发布管理  → "新版本怎么上线？出问题怎么回滚？"
```

---

## 2. 服务注册中心深度对比

### 2.1 Eureka vs Nacos vs Consul vs Zookeeper

| 特性 | Eureka | Nacos | Consul | Zookeeper |
|------|--------|-------|--------|-----------|
| **CAP 模型** | AP | AP（可切换 CP） | CP | CP |
| **一致性协议** | 无（自我保护） | Raft | Raft | ZAB |
| **健康检查** | 心跳 | 心跳 + TCP/HTTP | TCP/HTTP/gRPC | 心跳 |
| **配置管理** | 无 | ✅ 内置 | ✅ KV 存储 | ✅ ZNode |
| **服务分组** | 无 | ✅ 命名空间+组 | ✅ Tag | 无 |
| **界面** | 基础 | ✅ 完善 | ✅ 完善 | 无（需第三方） |
| **Spring Cloud 集成** | ✅ | ✅ | ✅ | 一般 |
| **维护状态** | 停止维护 | 活跃 | 活跃 | 活跃 |
| **推荐度** | 不推荐新项目 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |

### 2.2 迁移到 Nacos 的核心配置

```xml
<!-- 替换 Eureka 依赖 -->
<!-- 移除 -->
<!-- <dependency>spring-cloud-starter-netflix-eureka-client</dependency> -->

<!-- 添加 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

```yaml
spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: nacos:8848
        namespace: prod              # 命名空间，用于环境隔离
        group: DEFAULT_GROUP
        metadata:
          version: v1.2.0
          region: cn-east
      config:
        server-addr: nacos:8848
        namespace: prod
        file-extension: yaml
        # 从 Nacos 加载的配置文件：order-service.yaml
        shared-configs:
          - data-id: common.yaml     # 多个服务共享的公共配置
            group: SHARED_GROUP
            refresh: true
```

---

## 3. 流量治理

### 3.1 灰度发布（Canary Release）

灰度发布：新版本只对部分用户/流量生效，降低发布风险。

```
流量路由规则：
总流量 100%
├── 90% → 稳定版本 v1 （老版本）
└── 10% → 灰度版本 v2 （新版本）

观察 v2 运行情况无误后，逐步扩大 v2 比例，直到 100%
```

#### 基于 Spring Cloud Gateway + 请求头的灰度路由

```java
// 自定义灰度路由过滤器工厂
@Component
public class GrayRouteFilter implements GlobalFilter, Ordered {

    private static final String GRAY_HEADER = "X-Gray-Version";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String grayVersion = request.getHeaders().getFirst(GRAY_HEADER);

        if ("v2".equals(grayVersion)) {
            // 携带灰度标记，转发到 v2 实例
            URI grayUri = URI.create("lb://order-service-v2" + request.getPath());
            ServerWebExchange mutatedExchange = exchange.mutate()
                .request(request.mutate().uri(grayUri).build())
                .build();
            return chain.filter(mutatedExchange);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -5;
    }
}
```

#### 基于权重的随机灰度（不依赖请求头）

```yaml
# application.yml（API Gateway）
spring:
  cloud:
    gateway:
      routes:
        # 10% 流量打到 v2
        - id: order-service-canary
          uri: lb://order-service-v2
          predicates:
            - Weight=order-group, 10
          filters:
            - AddRequestHeader=X-Version, v2

        # 90% 流量打到 v1
        - id: order-service-stable
          uri: lb://order-service
          predicates:
            - Weight=order-group, 90
```

### 3.2 蓝绿部署（Blue-Green Deployment）

```
蓝环境（当前生产） ← 100% 流量
绿环境（新版本）   ← 0% 流量（准备中）

部署完成、测试通过后：
蓝环境 ← 0% 流量（保留，用于快速回滚）
绿环境 ← 100% 流量

出现问题时：瞬间切回蓝环境
```

```bash
# Kubernetes 蓝绿部署：通过 Service selector 切换
# 切换到绿环境（只需改 selector）
kubectl patch service order-service \
  -p '{"spec":{"selector":{"version":"green"}}}'

# 回滚到蓝环境
kubectl patch service order-service \
  -p '{"spec":{"selector":{"version":"blue"}}}'
```

### 3.3 Sentinel 流量控制

Sentinel 是 Alibaba 开源的流量防护框架，功能比 Resilience4j 更丰富：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: sentinel-dashboard:8080  # Sentinel 控制台地址
      eager: true
```

```java
@Service
public class OrderService {

    @SentinelResource(
        value = "createOrder",
        blockHandler = "createOrderBlockHandler",    // 被限流/熔断时
        fallback = "createOrderFallback"             // 业务异常降级时
    )
    public Order createOrder(OrderRequest request) {
        // 业务逻辑
    }

    // 限流处理（BlockException 参数必须有）
    public Order createOrderBlockHandler(OrderRequest request, BlockException ex) {
        log.warn("Order creation blocked: {}", ex.getClass().getSimpleName());
        throw new BusinessException(429, "系统繁忙，请稍后再试");
    }

    // 降级处理
    public Order createOrderFallback(OrderRequest request, Throwable t) {
        log.error("Order creation fallback triggered", t);
        throw new BusinessException(503, "服务暂时不可用");
    }
}
```

Sentinel 控制台可以**动态修改**流控规则，无需重启服务：

```
流控规则配置（Sentinel 控制台）：
资源名: createOrder
限流阈值: 100 QPS
流控模式: 直接
流控效果: 快速失败
```

---

## 4. 动态配置治理

### 4.1 配置的分类管理

```
配置层次：
┌─────────────────────────────────────────────────┐
│ 公共配置（所有服务共享）                           │
│ common.yaml: 日志级别、全局超时、公共 Redis 地址   │
├─────────────────────────────────────────────────┤
│ 服务私有配置                                      │
│ order-service.yaml: 订单业务参数、数据源           │
├─────────────────────────────────────────────────┤
│ 环境配置（环境差异）                               │
│ order-service-prod.yaml: 生产环境覆盖参数          │
└─────────────────────────────────────────────────┘
优先级：环境配置 > 服务私有 > 公共配置
```

### 4.2 配置热更新

使用 `@RefreshScope` 实现配置热更新，无需重启服务：

```java
@RestController
@RefreshScope   // 配置变更时自动刷新 Bean
public class OrderController {

    @Value("${order.max-items-per-order:10}")
    private int maxItemsPerOrder;

    // 每次请求时读取的是最新值
    @PostMapping("/orders")
    public ApiResponse<?> createOrder(@RequestBody OrderRequest request) {
        if (request.getQuantity() > maxItemsPerOrder) {
            throw new BusinessException(400,
                "每单最多购买 " + maxItemsPerOrder + " 件");
        }
        // ...
    }
}
```

配置变更流程（Nacos）：

```
1. 开发者在 Nacos 控制台修改配置
         ↓
2. Nacos 推送配置变更通知到各服务
         ↓
3. @RefreshScope Bean 自动刷新
         ↓
4. 服务行为立即改变（无需重启）
```

### 4.3 配置加密

敏感配置（数据库密码、密钥）不应明文存储：

```yaml
# Jasypt 加密配置
jasypt:
  encryptor:
    password: ${JASYPT_ENCRYPTOR_PASSWORD}  # 从环境变量读取加密密钥
    algorithm: PBEWithMD5AndDES

spring:
  datasource:
    # ENC() 包裹的值会被 Jasypt 自动解密
    password: ENC(Jxl+XE7HEMa5BMxmKEIrRA==)
```

```java
// 生成加密值
StrongPasswordEncryptor encryptor = new StrongPasswordEncryptor();
String encrypted = encryptor.encryptPassword("my-db-password");
// 在 Nacos 中配置: ENC(encrypted_value)
```

---

## 5. 服务 Catalog 与 API 管理

### 5.1 为什么需要服务目录？

服务数量多时，面临的问题：

- **发现难**：我想用订单服务的某个接口，文档在哪？
- **依赖不清**：用户服务下线会影响哪些服务？
- **版本混乱**：用的是哪个版本的接口？有没有废弃？
- **负责人不明**：这个服务有问题找谁？

### 5.2 集成 Springdoc（OpenAPI 3）

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.5.0</version>
</dependency>
```

```java
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Order Service API")
                .version("v1.2.0")
                .description("订单服务 API 文档")
                .contact(new Contact()
                    .name("Backend Team")
                    .email("backend@example.com")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components()
                .addSecuritySchemes("bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
```

```java
// 接口注解，生成标准文档
@Tag(name = "订单管理", description = "订单的增删改查接口")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Operation(
        summary = "创建订单",
        description = "创建新订单，库存不足时返回 400"
    )
    @ApiResponse(responseCode = "200", description = "创建成功")
    @ApiResponse(responseCode = "400", description = "库存不足或参数错误")
    @PostMapping
    public ApiResponse<Order> createOrder(
            @Parameter(description = "订单请求体", required = true)
            @RequestBody @Valid OrderRequest request) {
        return ApiResponse.success(orderService.createOrder(request));
    }
}
```

访问地址：`http://localhost:8081/swagger-ui.html`

### 5.3 API Gateway 聚合多服务文档

```java
// 在 API Gateway 聚合所有服务的 Swagger UI
@Configuration
public class SwaggerAggregatorConfig {

    @Bean
    public List<GroupedOpenApi> apis() {
        return List.of(
            buildApi("user-service", "/api/users/**"),
            buildApi("order-service", "/api/orders/**"),
            buildApi("product-service", "/api/products/**")
        );
    }

    private GroupedOpenApi buildApi(String name, String pathPattern) {
        return GroupedOpenApi.builder()
            .group(name)
            .pathsToMatch(pathPattern)
            .build();
    }
}
```

---

## 6. 服务依赖拓扑管理

### 6.1 依赖关系追踪

微服务的调用依赖关系可以从分布式追踪数据中提取（Zipkin/Jaeger），也可以通过代码分析工具生成：

```
依赖拓扑示例：

API Gateway
├── user-service
├── order-service
│   ├── user-service（Feign 调用）
│   ├── product-service（Feign 调用）
│   └── rabbitmq（消息发布）
└── product-service

关键问题识别：
- order-service 直接依赖 2 个服务 → 需要熔断保护
- user-service 被多个服务依赖 → 高优先级，要保稳定
```

### 6.2 通过 Actuator 暴露依赖信息

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, mappings
  info:
    env:
      enabled: true

# 服务信息
info:
  app:
    name: order-service
    version: "@project.version@"
    description: 订单服务
    team: backend-team
    dependencies:
      - user-service
      - product-service
      - mysql
      - redis
      - rabbitmq
```

---

## 7. SLA 与服务契约

### 7.1 定义服务 SLA

```yaml
# 服务 SLA 定义（可存在 Nacos 配置中）
sla:
  order-service:
    availability: 99.9%          # 可用性目标
    response-time:
      p50: 100ms
      p95: 300ms
      p99: 1000ms
    throughput: 1000 QPS         # 吞吐量目标
    error-rate: < 0.1%           # 错误率目标
```

### 7.2 消费者驱动契约测试（CDC）

契约测试确保 Provider（服务提供方）不会破坏 Consumer（服务消费方）的期望：

```java
// Consumer 端定义契约（使用 Pact）
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "product-service")
class ProductClientContractTest {

    @Pact(consumer = "order-service")
    public RequestResponsePact getProductPact(PactDslWithProvider builder) {
        return builder
            .given("product with id 1 exists")
            .uponReceiving("get product by id")
            .path("/products/1")
            .method("GET")
            .willRespondWith()
            .status(200)
            .body(new PactDslJsonBody()
                .integerType("id", 1)
                .stringType("name", "iPhone 15")
                .decimalType("price", 7999.00))
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getProductPact")
    void testGetProduct(MockServer mockServer) {
        ProductClient client = buildClient(mockServer.getUrl());
        ProductDTO product = client.getProductById(1L);
        assertThat(product.getId()).isEqualTo(1L);
    }
}
```

```
契约测试流程：
1. Consumer 定义期望（契约文件 .json）
          ↓
2. 契约文件上传到 Pact Broker（集中管理）
          ↓
3. Provider CI 中拉取契约，验证自己是否满足所有消费方期望
          ↓
4. 任何 Provider 变更破坏契约时，CI 失败，阻止发布
```

---

## 8. 微服务治理平台选型

| 平台 | 适用场景 | 核心能力 |
|------|---------|---------|
| **Spring Cloud Alibaba + Nacos** | Java 系，国内主流 | 注册、配置、流控（Sentinel）|
| **Istio (Service Mesh)** | 多语言，K8s 场景 | 流量管理、mTLS、可观测性 |
| **Kong + Kuma** | 云原生，多协议 | API 网关 + Service Mesh |
| **Dapr** | 多语言，分布式运行时 | 服务调用、状态管理、发布订阅 |
| **自研 + 开源组件** | 高度定制化需求 | 按需组合 |

### 推荐架构（Java 微服务）

```
推荐技术选型（2024+）：

注册 & 配置  →  Nacos
流量控制    →  Sentinel + Spring Cloud Gateway
链路追踪    →  Micrometer Tracing + Zipkin/Tempo
指标监控    →  Micrometer + Prometheus + Grafana
日志聚合    →  ELK（Elasticsearch + Logstash + Kibana）
容器编排    →  Kubernetes
服务网格    →  Istio（规模足够大时引入）
API 文档    →  Springdoc (OpenAPI 3)
```

---

## 9. 治理成熟度模型

对照检查自己团队的治理水平：

```
Level 1 - 初级（能跑起来）
  □ 服务注册与发现
  □ 基础健康检查
  □ 日志记录

Level 2 - 中级（能监控）
  □ 集中配置管理（支持热更新）
  □ 熔断 & 限流
  □ 分布式链路追踪
  □ 指标监控 + 告警

Level 3 - 高级（能治理）
  □ 灰度发布 / 蓝绿部署
  □ 服务依赖拓扑可视化
  □ 契约测试（CDC）
  □ API 版本管理
  □ 多租户支持
  □ SLA 定义与监控

Level 4 - 专家（全自动）
  □ 自动弹性伸缩（基于 SLA 触发）
  □ 混沌工程（Chaos Engineering）
  □ 自动故障恢复
  □ 服务网格（Istio/Linkerd）
  □ GitOps（ArgoCD/Flux）
```

---

## 10. 延伸阅读

- 📖 [Sentinel 官方文档](https://sentinelguard.io/zh-cn/docs/introduction.html)
- 📖 [Nacos 官方文档](https://nacos.io/zh-cn/docs/what-is-nacos.html)
- 📖 [Pact 契约测试](https://docs.pact.io/)
- 📖 [Google SRE Book - Service Level Objectives](https://sre.google/sre-book/service-level-objectives/)
- 📖《微服务架构设计模式》- Chris Richardson（第 11-12 章）

---

**上一篇：** [24 - 微服务性能优化](./24-performance-optimization.md)
