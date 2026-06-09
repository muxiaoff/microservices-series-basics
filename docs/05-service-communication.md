# 微服务系列 05 - 服务间通信

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 服务间通信概述

微服务之间的通信是整个架构的核心问题。选择合适的通信方式，直接决定了系统的性能、可靠性和复杂度。

```
┌───────────┐     同步通信       ┌───────────┐
│  Service A │───REST/gRPC──────▶│  Service B │
└───────────┘                    └───────────┘

┌───────────┐     异步通信       ┌───────────┐
│  Service A │──Message Queue──▶│  Service B │
└───────────┘                    └───────────┘
```

### 1.1 通信方式对比

| 维度 | 同步通信 | 异步通信 |
|------|----------|----------|
| **协议** | REST (HTTP)、gRPC | 消息队列 (RabbitMQ/Kafka) |
| **响应** | 实时等待 | 无需等待 |
| **耦合度** | 较高 | 较低 |
| **一致性** | 强一致（相对） | 最终一致 |
| **性能** | 依赖下游延迟 | 消息队列吞吐 |
| **可靠性** | 需要重试/熔断 | 消息持久化保证 |
| **复杂度** | 简单 | 较高 |
| **适用场景** | 查询、需要即时响应 | 命令、事件通知、解耦 |

---

## 2. RESTful API 通信

### 2.1 RestTemplate

Spring Boot 自带的 HTTP 客户端，最基础的方式：

```java
@Service
public class OrderService {

    @Autowired
    private RestTemplate restTemplate;

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // GET 请求
    public User getUser(Long userId) {
        return restTemplate.getForObject(
            "http://user-service/api/users/{id}",
            User.class,
            userId
        );
    }

    // POST 请求
    public Order createOrder(OrderRequest request) {
        return restTemplate.postForObject(
            "http://order-service/api/orders",
            request,
            Order.class
        );
    }

    // PUT 请求
    public void updateUser(Long userId, User user) {
        restTemplate.put(
            "http://user-service/api/users/{id}",
            user,
            userId
        );
    }

    // DELETE 请求
    public void deleteUser(Long userId) {
        restTemplate.delete(
            "http://user-service/api/users/{id}",
            userId
        );
    }
}
```

### 2.2 OpenFeign（推荐）

Feign 是声明式的 HTTP 客户端，让调用远程服务像调用本地方法一样简单。

**依赖：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

**启用 Feign：**

```java
@SpringBootApplication
@EnableFeignClients  // 启用 Feign 客户端
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

**定义 Feign Client：**

```java
@FeignClient(
    name = "user-service",           // 目标服务名
    path = "/api/users",             // 基础路径
    fallbackFactory = UserClientFallbackFactory.class  // 降级处理
)
public interface UserClient {

    @GetMapping("/{id}")
    User getUserById(@PathVariable("id") Long id);

    @GetMapping
    List<User> getAllUsers();

    @PostMapping
    User createUser(@RequestBody UserRequest request);

    @PutMapping("/{id}")
    User updateUser(@PathVariable("id") Long id, @RequestBody UserRequest request);

    @DeleteMapping("/{id}")
    void deleteUser(@PathVariable("id") Long id);
}
```

**使用 Feign Client：**

```java
@Service
public class OrderServiceImpl {

    @Autowired
    private UserClient userClient;    // 像调用本地方法一样！

    @Autowired
    private ProductClient productClient;

    public OrderDetail getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId);
        
        // 远程调用用户服务
        User user = userClient.getUserById(order.getUserId());
        
        // 远程调用商品服务
        Product product = productClient.getProductById(order.getProductId());

        return OrderDetail.builder()
            .order(order)
            .userName(user.getName())
            .productName(product.getName())
            .build();
    }
}
```

### 2.3 Feign 降级处理

```java
@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        return new UserClient() {
            @Override
            public User getUserById(Long id) {
                // 降级返回默认用户
                return User.builder()
                    .id(id)
                    .name("未知用户")
                    .build();
            }

            @Override
            public List<User> getAllUsers() {
                return Collections.emptyList();
            }

            @Override
            public User createUser(UserRequest request) {
                throw new ServiceException("用户服务不可用");
            }

            @Override
            public User updateUser(Long id, UserRequest request) {
                throw new ServiceException("用户服务不可用");
            }

            @Override
            public void deleteUser(Long id) {
                throw new ServiceException("用户服务不可用");
            }
        };
    }
}
```

### 2.4 Feign 配置

```yaml
# Feign 全局配置
feign:
  client:
    config:
      default:                    # 全局默认配置
        connect-timeout: 5000     # 连接超时 5 秒
        read-timeout: 10000       # 读取超时 10 秒
        logger-level: basic       # 日志级别
      
      user-service:              # 针对特定服务的配置
        connect-timeout: 3000
        read-timeout: 5000

  # 启用断路器
  circuitbreaker:
    enabled: true
```

### 2.5 Feign 拦截器

```java
@Component
public class FeignAuthInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 传递当前请求的认证信息
        ServletRequestAttributes attributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String token = request.getHeader("Authorization");
            if (token != null) {
                template.header("Authorization", token);
            }
            
            // 传递追踪 ID
            String traceId = request.getHeader("X-Trace-Id");
            if (traceId != null) {
                template.header("X-Trace-Id", traceId);
            }
        }
    }
}
```

---

## 3. gRPC 通信

### 3.1 gRPC 简介

gRPC 是 Google 开源的高性能 RPC 框架，使用 Protocol Buffers 序列化，基于 HTTP/2 协议。

```
REST vs gRPC：

REST:
┌─────────┐  JSON over HTTP/1.1  ┌─────────┐
│ Client  │──────────────────────▶│ Server  │
└─────────┘                       └─────────┘
- 文本序列化（慢）
- HTTP/1.1（无多路复用）

gRPC:
┌─────────┐  Protobuf over HTTP/2 ┌─────────┐
│ Client  │───────────────────────▶│ Server  │
└─────────┘                        └─────────┘
- 二进制序列化（快）
- HTTP/2（多路复用、头部压缩）
- 强类型接口定义
```

### 3.2 定义 Proto 文件

```protobuf
// product.proto
syntax = "proto3";

package com.example.product;

option java_package = "com.example.product.grpc";

service ProductService {
  rpc GetProduct (GetProductRequest) returns (GetProductResponse);
  rpc ListProducts (ListProductsRequest) returns (ListProductsResponse);
}

message GetProductRequest {
  int64 id = 1;
}

message GetProductResponse {
  int64 id = 1;
  string name = 2;
  double price = 3;
  int32 stock = 4;
}

message ListProductsRequest {
  int32 page = 1;
  int32 size = 2;
}

message ListProductsResponse {
  repeated GetProductResponse products = 1;
  int32 total = 2;
}
```

### 3.3 gRPC 适用场景

- 高性能内部服务间调用
- 流式数据处理
- 多语言混合的技术栈
- 对延迟敏感的场景

---

## 4. 消息队列异步通信

### 4.1 消息队列核心概念

```
┌──────────┐   发布消息   ┌──────────────┐   消费消息   ┌──────────┐
│ Producer │─────────────▶│    Broker     │─────────────▶│Consumer  │
│(订单服务) │              │  (RabbitMQ)  │              │(库存服务) │
└──────────┘              │              │              └──────────┘
                          │  ┌────────┐  │
                          │  │Exchange│  │
                          │  └───┬────┘  │
                          │      │       │
                          │  ┌───▼────┐  │
                          │  │ Queue  │  │
                          │  └────────┘  │
                          └──────────────┘
```

### 4.2 RabbitMQ 示例

**依赖：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

**配置：**

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

**生产者（发送消息）：**

```java
@Service
public class OrderEventPublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void publishOrderCreated(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
            .orderId(order.getId())
            .userId(order.getUserId())
            .productId(order.getProductId())
            .quantity(order.getQuantity())
            .totalPrice(order.getTotalPrice())
            .createdAt(LocalDateTime.now())
            .build();

        rabbitTemplate.convertAndSend(
            "order.exchange",       // Exchange
            "order.created",        // Routing Key
            event                   // 消息体
        );
        
        log.info("Published order created event: {}", event.getOrderId());
    }
}
```

**消费者（接收消息）：**

```java
@Component
@Slf4j
public class OrderEventConsumer {

    @Autowired
    private ProductService productService;

    @RabbitListener(queues = "product.stock.deduct.queue")
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Received order created event: {}", event.getOrderId());
        
        try {
            // 扣减库存
            productService.deductStock(event.getProductId(), event.getQuantity());
            log.info("Stock deducted for product: {}", event.getProductId());
        } catch (Exception e) {
            log.error("Failed to deduct stock: {}", e.getMessage());
            throw e;  // 抛出异常触发重试
        }
    }
}
```

**队列配置：**

```java
@Configuration
public class RabbitMQConfig {

    // Exchange
    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange("order.exchange", true, false);
    }

    // Queue
    @Bean
    public Queue stockDeductQueue() {
        return QueueBuilder.durable("product.stock.deduct.queue")
            .withArgument("x-dead-letter-exchange", "order.dlx")
            .withArgument("x-dead-letter-routing-key", "order.failed")
            .build();
    }

    // Binding
    @Bean
    public Binding stockDeductBinding() {
        return BindingBuilder
            .bind(stockDeductQueue())
            .to(orderExchange())
            .with("order.created");
    }

    // 死信队列
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable("order.dead-letter.queue").build();
    }
}
```

### 4.3 Kafka 示例

**依赖：**

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

**生产者：**

```java
@Service
@Slf4j
public class OrderEventPublisher {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        String message = JsonUtils.toJson(event);
        kafkaTemplate.send("order-events", event.getOrderId().toString(), message)
            .addCallback(
                result -> log.info("Message sent: {}", result.getRecordMetadata()),
                ex -> log.error("Failed to send message: {}", ex.getMessage())
            );
    }
}
```

**消费者：**

```java
@Component
@Slf4j
public class OrderEventConsumer {

    @KafkaListener(topics = "order-events", groupId = "product-service-group")
    public void handleOrderCreated(ConsumerRecord<String, String> record) {
        OrderCreatedEvent event = JsonUtils.fromJson(record.value(), OrderCreatedEvent.class);
        log.info("Received order event: {}", event.getOrderId());
        // 处理事件...
    }
}
```

### 4.4 RabbitMQ vs Kafka

| 维度 | RabbitMQ | Kafka |
|------|----------|-------|
| **定位** | 消息代理 | 分布式流平台 |
| **吞吐量** | 万级/秒 | 百万级/秒 |
| **消息模型** | Exchange-Queue | Topic-Partition |
| **消息顺序** | 单队列有序 | 分区内有序 |
| **消息回溯** | 不支持 | 支持（按 offset） |
| **适用场景** | 业务消息、RPC | 日志、大数据流、事件溯源 |

---

## 5. 通信方式选择决策树

```
需要即时响应？
├── 是 → 同步通信
│        ├── 简单 CRUD → REST + Feign
│        └── 高性能 → gRPC
└── 否 → 异步通信
         ├── 简单消息、业务解耦 → RabbitMQ
         ├── 高吞吐、日志流 → Kafka
         └── 事件驱动架构 → Kafka + Event Sourcing
```

---

## 6. 通信可靠性保障

### 6.1 重试机制

```java
@FeignClient(
    name = "user-service",
    configuration = RetryConfig.class
)
public interface UserClient {
    @Retryable(
        value = { FeignException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @GetMapping("/{id}")
    User getUserById(@PathVariable("id") Long id);
}
```

### 6.2 幂等性保证

```
幂等性：同一操作执行一次和多次的效果相同

HTTP 方法幂等性：
- GET      → 幂等（查询不改变状态）
- PUT      → 幂等（全量更新，结果一致）
- DELETE   → 幂等（删除一次和多次结果相同）
- POST     → 非幂等（可能创建多次）
- PATCH    → 可能非幂等

实现幂等的方法：
1. 唯一请求 ID（Idempotency Key）
2. 数据库唯一约束
3. 乐观锁（版本号）
4. 状态机（只允许特定状态转换）
```

### 6.3 超时设置

```yaml
# 应用层超时
feign:
  client:
    config:
      default:
        connect-timeout: 3000
        read-timeout: 5000

# 也可以在代码中设置
@FeignClient(name = "user-service", url = "${user-service.url}")
public interface UserClient {
    
    @GetMapping("/{id}")
    User getUserById(@PathVariable("id") Long id);
}
```

---

**上一篇：** [04 - API 网关](./04-api-gateway.md)  
**下一篇：** [06 - 配置管理](./06-configuration-management.md)
