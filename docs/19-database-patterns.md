# 微服务系列 19 - 数据库模式：CQRS、Event Sourcing 与分片

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 微服务数据库面临的挑战

每个服务拥有自己的数据库（数据自治），带来了新的挑战：

```
挑战 1：跨服务查询
┌──────────┐   ┌──────────┐   ┌──────────┐
│User DB   │   │Order DB  │   │Product DB│
│用户信息   │   │订单信息   │   │商品信息   │
└──────────┘   └──────────┘   └──────────┘
     ↑              ↑              ↑
     └──────────────┼──────────────┘
          如何查询"张三的所有订单及商品名称"？
          不能 JOIN！数据在不同数据库中

挑战 2：读写性能不平衡
写操作：少量、需要强一致性
读操作：大量、可以接受最终一致性
用同一个数据库模型很难同时优化

挑战 3：数据一致性
跨服务的数据如何保持一致？（已在第 9 章讨论）
```

---

## 2. 每服务一数据库

### 2.1 数据库隔离模式

| 模式 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **独占数据库实例** | 每个服务独立 DB 实例 | 完全隔离 | 资源浪费 |
| **独占 Schema** | 同一实例不同 Schema | 资源共享 | 实例级故障影响所有 |
| **独占表** | 同一 Schema 不同表前缀 | 简单 | 容易误访问 |

### 2.2 禁止直接访问其他服务数据库

```
❌ 错误：Order Service 直接查询 User DB
Order Service ──SQL──▶ User DB

✅ 正确：通过 API / 事件获取数据
Order Service ──Feign──▶ User Service ──SQL──▶ User DB
```

---

## 3. CQRS（命令查询职责分离）

### 3.1 核心思想

将读操作（Query）和写操作（Command）分离到不同的模型中：

```
传统模式（读写共用一个模型）：
┌──────────┐    ┌──────────┐    ┌──────────┐
│  Client   │───▶│  Service │───▶│ Database │
│ (读 + 写) │    │ (读写混合)│    │ (折中模型)│
└──────────┘    └──────────┘    └──────────┘
读和写互相妥协，模型不够优化

CQRS 模式（读写分离）：
┌──────────┐   Command   ┌──────────┐   ┌──────────┐
│  Client  │────────────▶│ Command  │──▶│ Write DB │
│          │             │ Model    │   │ (3NF)    │
│          │             └──────────┘   └─────┬────┘
│          │                                  │ Event
│          │   Query     ┌──────────┐   ┌─────▼────┐
│          │◀────────────│  Query   │◀──│  Read DB │
│          │             │  Model   │   │(反规范化) │
│          │             └──────────┘   └──────────┘
```

### 3.2 写模型（Command Model）

写模型关注业务规则和验证，使用规范化（3NF）的数据结构：

```java
// 写模型：关注业务规则
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal totalPrice;
    private String status;

    // 业务方法（封装业务规则）
    public void create(Long userId, Long productId, Integer quantity, BigDecimal price) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalPrice = price.multiply(BigDecimal.valueOf(quantity));
        this.status = "CREATED";
    }

    public void pay() {
        if (!"CREATED".equals(this.status)) {
            throw new IllegalStateException("Only CREATED order can be paid");
        }
        this.status = "PAID";
    }

    public void cancel() {
        if ("SHIPPED".equals(this.status)) {
            throw new IllegalStateException("Cannot cancel shipped order");
        }
        this.status = "CANCELLED";
    }
}
```

### 3.3 读模型（Query Model）

读模型关注查询性能，使用反规范化的数据结构：

```java
// 读模型：为查询优化（反规范化，包含关联信息）
@Entity
@Table(name = "order_view")
public class OrderView {

    @Id
    private Long id;

    // 订单信息
    private Long userId;
    private String userName;       // 冗余：来自 User Service
    private String userPhone;      // 冗余：来自 User Service
    private Long productId;
    private String productName;    // 冗余：来自 Product Service
    private BigDecimal productPrice; // 冗余
    private Integer quantity;
    private BigDecimal totalPrice;
    private String status;
    private LocalDateTime createdAt;
}
```

### 3.4 数据同步（事件驱动）

写模型变更后，通过事件同步到读模型：

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderViewSyncService {

    private final OrderViewRepository orderViewRepository;

    @KafkaListener(topics = "order-events", groupId = "order-view-sync")
    public void syncOrderView(String payload) {
        BaseEvent event = JsonUtils.fromJson(payload, BaseEvent.class);

        switch (event.getEventType()) {
            case "order.created" -> handleOrderCreated(payload);
            case "order.paid" -> handleOrderPaid(payload);
            case "order.cancelled" -> handleOrderCancelled(payload);
        }
    }

    private void handleOrderCreated(String payload) {
        OrderCreatedEvent event = JsonUtils.fromJson(payload, OrderCreatedEvent.class);

        OrderView view = new OrderView();
        view.setId(event.getOrderId());
        view.setUserId(event.getUserId());
        view.setUserName(event.getUserName());        // 从事件中获取
        view.setProductId(event.getProductId());
        view.setProductName(event.getProductName());  // 从事件中获取
        view.setQuantity(event.getQuantity());
        view.setTotalPrice(event.getTotalPrice());
        view.setStatus("CREATED");
        view.setCreatedAt(event.getTimestamp());

        orderViewRepository.save(view);
        log.info("OrderView synced: orderId={}", event.getOrderId());
    }
}
```

### 3.5 CQRS 适用场景

| 场景 | 说明 |
|------|------|
| **读写比例悬殊** | 读远多于写（如商品浏览） |
| **复杂查询** | 需要跨服务数据聚合 |
| **不同优化方向** | 写要强一致，读要高性能 |
| **团队分工** | 读写团队独立开发 |

### 3.6 CQRS 的代价

- **复杂度增加**：两套模型 + 数据同步
- **最终一致性**：读模型可能有延迟
- **运维成本**：两套数据库/表

---

## 4. Event Sourcing（事件溯源）

### 4.1 核心思想

不存储当前状态，而是存储所有状态变更事件。通过重放事件来重建状态：

```
传统方式：
┌────────────────────────────────────────┐
│ Order Table                             │
│ id=1 | userId=1 | status=SHIPPED | ... │  ← 只存最终状态
└────────────────────────────────────────┘

事件溯源：
┌─────────────────────────────────────────────────────┐
│ Event Store                                          │
│ seq=1 | OrderCreated    | {userId:1, productId:5}    │
│ seq=2 | PaymentCompleted| {paymentId:pay-123}        │
│ seq=3 | OrderShipped    | {trackingNo:SF123456}      │
│                                                      │
│ 重放事件 → 重建任意时间点的状态                        │
│ seq=1 后: status=CREATED                             │
│ seq=2 后: status=PAID                                │
│ seq=3 后: status=SHIPPED                             │
└─────────────────────────────────────────────────────┘
```

### 4.2 事件存储

```java
@Entity
@Table(name = "domain_events")
public class DomainEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sequence;

    private String aggregateType;    // 聚合类型: "ORDER"
    private Long aggregateId;        // 聚合 ID: 1
    private String eventType;        // 事件类型: "order.created"
    private String payload;          // 事件数据: JSON
    private LocalDateTime timestamp;
    private String metadata;         // 元数据
}
```

```java
@Repository
public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {

    List<DomainEvent> findByAggregateTypeAndAggregateIdOrderBySequenceAsc(
        String aggregateType, Long aggregateId);
}
```

### 4.3 聚合根

```java
public abstract class AggregateRoot {

    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    protected void applyEvent(DomainEvent event) {
        uncommittedEvents.add(event);
        handleEvent(event);  // 更新自身状态
    }

    public List<DomainEvent> getUncommittedEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    public void markEventsAsCommitted() {
        uncommittedEvents.clear();
    }

    protected abstract void handleEvent(DomainEvent event);
}
```

```java
public class OrderAggregate extends AggregateRoot {

    private Long id;
    private Long userId;
    private Long productId;
    private String status;

    // 从事件重建
    public void loadFromEvents(List<DomainEvent> events) {
        events.forEach(this::handleEvent);
    }

    // 创建订单
    public void create(Long userId, Long productId, Integer quantity) {
        DomainEvent event = DomainEvent.builder()
            .aggregateType("ORDER")
            .eventType("order.created")
            .payload(JsonUtils.toJson(Map.of(
                "userId", userId,
                "productId", productId,
                "quantity", quantity
            )))
            .timestamp(LocalDateTime.now())
            .build();
        applyEvent(event);
    }

    @Override
    protected void handleEvent(DomainEvent event) {
        switch (event.getEventType()) {
            case "order.created" -> {
                Map<String, Object> data = JsonUtils.fromJson(event.getPayload(), Map.class);
                this.id = event.getAggregateId();
                this.userId = ((Number) data.get("userId")).longValue();
                this.productId = ((Number) data.get("productId")).longValue();
                this.status = "CREATED";
            }
            case "order.paid" -> this.status = "PAID";
            case "order.shipped" -> this.status = "SHIPPED";
            case "order.cancelled" -> this.status = "CANCELLED";
        }
    }
}
```

### 4.4 Event Sourcing 适用场景

| 场景 | 说明 |
|------|------|
| **审计需求** | 金融、医疗等需要完整变更历史 |
| **时间旅行** | 需要查询任意时间点的状态 |
| **事件回放** | Bug 修复后重放事件重建状态 |
| **复杂状态机** | 状态转换逻辑复杂 |

### 4.5 Event Sourcing 的代价

- 学习曲线陡峭
- 事件存储膨胀（需要快照优化）
- 查询困难（需要 CQRS 配合）
- 最终一致性

---

## 5. 数据分片（Sharding）

### 5.1 分片策略

```
单库单表瓶颈 → 分库分表

水平分片（按行拆分）：
┌──────────┐    ┌──────────┐    ┌──────────┐
│ Shard 0  │    │ Shard 1  │    │ Shard 2  │
│ userId   │    │ userId   │    │ userId   │
│ 1-100万  │    │ 100-200万│    │ 200-300万│
└──────────┘    └──────────┘    └──────────┘

垂直分片（按列/表拆分）：
┌──────────────┐    ┌──────────────┐
│ 用户基本信息   │    │ 用户扩展信息   │
│ id, name     │    │ id, bio      │
│ phone, email │    │ avatar, settings│
└──────────────┘    └──────────────┘
```

### 5.2 分片键选择

| 策略 | 说明 | 示例 |
|------|------|------|
| **取模分片** | id % shardCount | userId % 3 → 0,1,2 |
| **范围分片** | 按 ID 范围 | 1-100万→S0, 100-200万→S1 |
| **哈希分片** | hash(key) % N | 一致性哈希 |
| **目录分片** | 查映射表 | 灵活但有单点风险 |

### 5.3 分片后的挑战

| 挑战 | 解决方案 |
|------|----------|
| **跨分片查询** | 应用层聚合 / 冗余数据 |
| **跨分片事务** | Saga / TCC |
| **分片再平衡** | 一致性哈希 / 虚拟桶 |
| **唯一 ID** | Snowflake / UUID |
| **聚合统计** | 预计算 / 读模型 |

---

## 6. 模式选择指南

```
数据量小 + 读写均衡？
├── 是 → 传统单库即可
└── 否 → 继续判断
         ├── 读远多于写？
         │   ├── 是 → CQRS
         │   └── 否 → 继续判断
         ├── 需要审计追踪？
         │   ├── 是 → Event Sourcing + CQRS
         │   └── 否 → 继续判断
         ├── 单表数据量超千万？
         │   ├── 是 → Sharding
         │   └── 否 → 优化索引和查询
         └── 组合场景
             ├── CQRS + Sharding
             ├── Event Sourcing + CQRS
             └── Sharding + 读写分离
```

---

**上一篇：** [18 - 微服务测试策略](./18-testing-microservices.md)  
**下一篇：** [20 - BFF 与 API 聚合](./20-bff-api-aggregation.md)