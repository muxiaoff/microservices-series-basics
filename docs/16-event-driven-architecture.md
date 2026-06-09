# 微服务系列 16 - 事件驱动架构

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 什么是事件驱动架构？

事件驱动架构（Event-Driven Architecture, EDA）是一种以**事件**为核心的架构模式。服务之间不再直接调用，而是通过发布和订阅事件进行通信。

```
同步调用（请求-响应）：
User Svc ←──── Order Svc ────→ Product Svc
         请求     │     请求
                 等待响应

事件驱动（发布-订阅）：
                     ┌───▶ User Svc（订阅：order.created → 发欢迎消息）
                     │
Order Svc ──▶ Event Bus ──▶ Inventory Svc（订阅：order.created → 扣库存）
                     │
                     └───▶ Analytics Svc（订阅：order.created → 统计分析）

发布者不关心谁消费，消费者互不干扰
```

### 1.1 核心概念

| 概念 | 说明 | 类比 |
|------|------|------|
| **Event** | 已发生的事实（过去时态） | "订单已创建" |
| **Producer** | 发布事件的服务 | 快递寄件人 |
| **Consumer** | 订阅并处理事件的服务 | 快递收件人 |
| **Event Bus / Broker** | 事件传输中介 | 快递公司 |
| **Topic / Channel** | 事件分类 | 快递线路 |
| **Schema** | 事件的数据结构 | 快递单格式 |

### 1.2 EDA 的优势

| 优势 | 说明 |
|------|------|
| **松耦合** | 生产者不知道消费者的存在 |
| **可扩展** | 增加消费者不影响生产者 |
| **异步** | 慢操作不阻塞主流程 |
| **事件回放** | 可以重新消费历史事件 |
| **审计追踪** | 事件是不可变的事实记录 |
| **响应式** | 系统对变化实时响应 |

---

## 2. 事件模式

### 2.1 事件通知（Event Notification）

最简单的事件模式——只告知发生了什么，消费者需要自己获取详情：

```json
{
  "eventId": "evt-12345",
  "eventType": "order.created",
  "timestamp": "2024-01-15T10:30:00Z",
  "source": "order-service",
  "data": {
    "orderId": 10086,
    "userId": 1,
    "productId": 5,
    "quantity": 2
  }
}
```

### 2.2 事件溯源（Event Sourcing）

不存储当前状态，而是存储所有状态变更事件。通过重放事件来重建状态：

```
传统方式：直接存储当前状态
┌──────────────────────────┐
│ Order Table              │
│ id=1, status=SHIPPED     │   ← 只有最终状态
└──────────────────────────┘

事件溯源：存储所有事件
┌───────────────────────────────────────────┐
│ Event Store                                │
│ 1. OrderCreated     → {status: CREATED}    │
│ 2. PaymentCompleted → {status: PAID}      │
│ 3. OrderShipped     → {status: SHIPPED}    │
│                                            │
│ 重放事件 → 重建任意时间点的状态              │
└───────────────────────────────────────────┘
```

### 2.3 CQRS（命令查询职责分离）

将写入（Command）和读取（Query）分离，通常与事件溯源配合使用：

```
┌──────────┐   Command    ┌──────────┐   Event   ┌──────────┐
│  Client  │─────────────▶│ Command  │──────────▶│  Event   │
│          │              │  Side    │           │  Store   │
│          │              │ (Write)  │           │          │
│          │              └──────────┘           └────┬─────┘
│          │                                          │
│          │   Query     ┌──────────┐   Subscribe    │
│          │◀────────────│  Query   │◀───────────────┘
│          │             │  Side    │
│          │             │ (Read)   │
│          │             └──────────┘
└──────────┘

写模型：关注业务规则和验证（规范化）
读模型：关注查询性能（反规范化、物化视图）
```

---

## 3. 事件设计原则

### 3.1 事件命名规范

```
✅ 好的事件名：
- order.created       （过去时态，表示已发生的事实）
- payment.completed
- user.registered
- inventory.deducted

❌ 不好的事件名：
- create_order        （命令，不是事件）
- order_changed       （模糊，不知道什么变了）
- orderEvent          （没有具体类型）
```

### 3.2 事件 Schema 设计

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "eventId": {
      "type": "string",
      "format": "uuid"
    },
    "eventType": {
      "type": "string",
      "enum": ["order.created", "order.cancelled", "order.shipped"]
    },
    "eventVersion": {
      "type": "string",
      "const": "1.0"
    },
    "timestamp": {
      "type": "string",
      "format": "date-time"
    },
    "source": {
      "type": "string"
    },
    "correlationId": {
      "type": "string",
      "format": "uuid"
    },
    "data": {
      "type": "object",
      "properties": {
        "orderId": { "type": "integer" },
        "userId": { "type": "integer" },
        "items": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "productId": { "type": "integer" },
              "quantity": { "type": "integer" },
              "price": { "type": "number" }
            }
          }
        },
        "totalAmount": { "type": "number" }
      },
      "required": ["orderId", "userId", "items"]
    }
  },
  "required": ["eventId", "eventType", "eventVersion", "timestamp", "source", "data"]
}
```

### 3.3 事件版本化

当事件结构需要变更时，必须考虑向后兼容：

| 策略 | 说明 | 示例 |
|------|------|------|
| **向前兼容** | 新增可选字段 | 加 `couponCode?: string` |
| **新事件类型** | 创建新版本事件 | `order.created.v2` |
| **版本字段** | 事件携带版本号 | `eventVersion: "2.0"` |
| **Schema Registry** | 集中管理 Schema | Confluent Schema Registry |

---

## 4. 实战：Spring Boot + Kafka 事件驱动

### 4.1 事件基类

```java
package com.example.common.event;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public abstract class BaseEvent {

    private String eventId;
    private String eventType;
    private String eventVersion;
    private LocalDateTime timestamp;
    private String source;
    private String correlationId;

    public BaseEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
        this.eventVersion = "1.0";
    }

    public abstract String getEventType();
}
```

### 4.2 领域事件

```java
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderCreatedEvent extends BaseEvent {

    private Long orderId;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal totalPrice;

    @Override
    public String getEventType() {
        return "order.created";
    }
}

@Data
@EqualsAndHashCode(callSuper = true)
public class OrderCancelledEvent extends BaseEvent {

    private Long orderId;
    private String reason;

    @Override
    public String getEventType() {
        return "order.cancelled";
    }
}
```

### 4.3 事件发布器

```java
@Service
@Slf4j
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String topic, BaseEvent event) {
        String key = event.getEventId();
        String value = JsonUtils.toJson(event);

        kafkaTemplate.send(topic, key, value)
            .addCallback(
                result -> log.info("Event published: topic={}, eventType={}, eventId={}",
                    topic, event.getEventType(), event.getEventId()),
                ex -> log.error("Failed to publish event: topic={}, eventType={}",
                    topic, event.getEventType(), ex)
            );
    }
}
```

### 4.4 业务服务发布事件

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public Order createOrder(OrderRequest request) {
        // 1. 创建订单
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setProductId(request.getProductId());
        order.setQuantity(request.getQuantity());
        order.setStatus("CREATED");
        orderRepository.save(order);

        // 2. 发布领域事件
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(order.getId());
        event.setUserId(order.getUserId());
        event.setProductId(order.getProductId());
        event.setQuantity(order.getQuantity());
        event.setTotalPrice(order.getTotalPrice());
        event.setSource("order-service");

        eventPublisher.publish("order-events", event);

        return order;
    }
}
```

### 4.5 事件消费者

```java
@Component
@Slf4j
public class InventoryEventConsumer {

    @Autowired
    private ProductService productService;

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void handleOrderEvent(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record.value());

        switch (eventType) {
            case "order.created" -> handleOrderCreated(record.value());
            case "order.cancelled" -> handleOrderCancelled(record.value());
            default -> log.warn("Unknown event type: {}", eventType);
        }
    }

    private void handleOrderCreated(String payload) {
        OrderCreatedEvent event = JsonUtils.fromJson(payload, OrderCreatedEvent.class);
        log.info("Processing order.created: orderId={}", event.getOrderId());

        try {
            productService.deductStock(event.getProductId(), event.getQuantity());
            log.info("Stock deducted for order: {}", event.getOrderId());
        } catch (Exception e) {
            log.error("Failed to deduct stock for order {}: {}",
                event.getOrderId(), e.getMessage());
            // 可以发布补偿事件或进入死信队列
        }
    }

    private void handleOrderCancelled(String payload) {
        OrderCancelledEvent event = JsonUtils.fromJson(payload, OrderCancelledEvent.class);
        log.info("Processing order.cancelled: orderId={}", event.getOrderId());
        // 恢复库存逻辑...
    }
}
```

---

## 5. 事件编排 vs 事件协同

### 5.1 编排式（Orchestration）

由一个中心协调器指挥所有参与者：

```
┌──────────────────┐
│  Saga Orchestrator│
│                  │
│ 1.→ 创建订单     │
│ 2.→ 扣减库存     │
│ 3.→ 扣减余额     │
│ 4.→ 确认订单     │
│                  │
│ 失败时反向补偿    │
└──────────────────┘
```

### 5.2 协同式（Choreography）

没有中心协调器，服务通过事件自然联动：

```
Order Service ──(order.created)──▶ Inventory Service
                                      │
                                      ├── 扣减库存
                                      │
                                  (stock.deducted)
                                      │
                                      ▼
                                 Payment Service
                                      │
                                      ├── 扣减余额
                                      │
                                  (payment.completed)
                                      │
                                      ▼
                                 Order Service
                                      │
                                      └── 确认订单
```

### 5.3 对比

| 维度 | 编排式 | 协同式 |
|------|--------|--------|
| **可见性** | 高（集中管理流程） | 低（分散在各服务） |
| **耦合度** | 协调器与参与者耦合 | 完全松耦合 |
| **复杂流程** | 适合 | 不适合 |
| **调试** | 集中日志 | 需要追踪 |
| **扩展** | 需修改协调器 | 加消费者即可 |

---

## 6. 事件驱动架构的挑战

### 6.1 事件顺序

```
问题：事件可能乱序到达
┌──────────┐  event-3  ┌──────────┐
│Producer  │───────▶    │Consumer  │  ← 先收到 event-3？
│          │  event-1  │          │
│          │───────▶    │          │  ← 后收到 event-1？
└──────────┘           └──────────┘

解决方案：
1. Kafka 分区有序（同一 key 路由到同一分区）
2. 事件携带时间戳/版本号，消费者自行排序
3. 幂等消费，忽略过期事件
```

### 6.2 幂等消费

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class IdempotentEventConsumer {

    private final ProcessedEventRepository processedEventRepository;

    public void processEvent(BaseEvent event, Runnable handler) {
        // 检查是否已处理
        if (processedEventRepository.existsByEventId(event.getEventId())) {
            log.info("Event already processed, skipping: {}", event.getEventId());
            return;
        }

        // 执行业务逻辑
        handler.run();

        // 记录已处理的事件
        processedEventRepository.save(
            new ProcessedEvent(event.getEventId(), event.getEventType()));
    }
}
```

### 6.3 死信队列

```java
@RetryableTopic(
    attempts = "3",
    backoff = @Backoff(delay = 1000, multiplier = 2),
    dltTopicSuffix = ".DLT",
    retryTopicSuffix = ".retry"
)
@KafkaListener(topics = "order-events")
public void handleEvent(String payload) {
    // 处理逻辑
}

@KafkaListener(topics = "order-events.DLT")
public void handleDeadLetter(String payload) {
    log.error("Event moved to DLT: {}", payload);
    // 告警 + 人工处理
}
```

---

## 7. 事件驱动适用场景

| 场景 | 示例 |
|------|------|
| **异步工作流** | 下单后异步扣库存、发通知 |
| **数据同步** | 服务间数据复制 |
| **审计日志** | 记录所有状态变更 |
| **实时通知** | WebSocket 推送 |
| **数据分析** | 事件 → 数据仓库 |
| **CQRS 读写分离** | 写入事件 → 更新读模型 |

---

**上一篇：** [15 - 服务网格与 Istio](./15-service-mesh.md)  
**下一篇：** [17 - API 设计与版本化](./17-api-design-versioning.md)