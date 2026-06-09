# 微服务系列 09 - 分布式事务

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么分布式事务这么难？

单体应用中，本地事务（ACID）轻松搞定：

```java
@Transactional
public void createOrder(OrderRequest request) {
    // 1. 创建订单
    orderRepository.save(order);
    // 2. 扣减库存
    inventoryRepository.deduct(productId, quantity);
    // 3. 扣减余额
    accountRepository.deduct(userId, totalPrice);
    // 要么全部成功，要么全部回滚 → 简单！
}
```

微服务中，数据分散在不同服务，本地事务无法覆盖：

```
Order Service → Order DB    ← 本地事务只能管到这里
Product Service → Product DB ← 跨了数据库！
Account Service → Account DB ← 也跨了！
```

**CAP 定理告诉我们**：分布式系统不可能同时满足一致性（C）、可用性（A）和分区容错（P）。在 P 不可避免的情况下，我们只能在 C 和 A 之间做选择。

---

## 2. 分布式事务方案概览

| 方案 | 一致性 | 性能 | 复杂度 | 适用场景 |
|------|--------|------|--------|----------|
| **2PC** | 强一致 | 低 | 中 | 传统数据库 |
| **TCC** | 最终一致 | 高 | 高 | 资金类 |
| **Saga** | 最终一致 | 高 | 中 | 长流程业务 |
| **本地消息表** | 最终一致 | 中 | 低 | 简单场景 |
| **事务消息** | 最终一致 | 高 | 中 | 事件驱动 |
| **Seata AT** | 最终一致 | 中 | 低 | 快速接入 |

---

## 3. 2PC（两阶段提交）

### 3.1 原理

```
第一阶段：Prepare（准备）
┌──────────┐         ┌──────────┐         ┌──────────┐
│Coordinator│──Prepare▶│Resource 1│         │Resource 2│
│(协调者)   │──Prepare─────────────────────▶│          │
└──────────┘         └──────────┘         └──────────┘
     │                     │                      │
     ◀───── OK ────────────┘                      │
     ◀───────────────────────── OK ───────────────┘

第二阶段：Commit（提交）
┌──────────┐──Commit──▶│Resource 1│──Commit──▶│Resource 2│
└──────────┘         └──────────┘         └──────────┘

如果第一阶段有任何一个返回失败 → 全部 Rollback
```

### 3.2 2PC 的问题

- **同步阻塞**：Prepare 后资源被锁定，直到 Commit/Rollback
- **单点故障**：协调者挂了，参与者永远阻塞
- **数据不一致**：第二阶段网络问题可能导致部分提交
- **性能差**：锁持有时间长，不适合高并发

> 2PC 在微服务中基本不用，了解即可。

---

## 4. TCC（Try-Confirm-Cancel）

### 4.1 原理

TCC 将一个事务拆分为三个阶段：

```
Try（尝试）：预留资源
├── 冻结账户余额（不是真正扣减）
├── 预扣库存（不是真正扣减）
└── 创建订单（状态为"待确认"）

Confirm（确认）：确认执行
├── 扣减冻结的余额
├── 扣减预扣的库存
└── 更新订单状态为"已确认"

Cancel（取消）：回滚
├── 解冻账户余额
├── 释放预扣库存
└── 更新订单状态为"已取消"
```

### 4.2 代码示例

```java
// === 账户服务 ===
public interface AccountTccService {
    
    // Try: 冻结金额
    @Transactional
    void tryDeduct(Long userId, BigDecimal amount, String txId) {
        Account account = accountRepository.findByUserId(userId);
        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException("余额不足");
        }
        // 冻结金额
        account.setAvailableBalance(
            account.getAvailableBalance().subtract(amount));
        account.setFrozenAmount(
            account.getFrozenAmount().add(amount));
        accountRepository.save(account);
        
        // 记录事务日志
        tccTransactionLogRepository.save(
            new TccTransactionLog(txId, "TRY", userId, amount));
    }
    
    // Confirm: 确认扣减
    @Transactional
    void confirmDeduct(Long userId, BigDecimal amount, String txId) {
        TccTransactionLog log = tccTransactionLogRepository
            .findByTxId(txId);
        if (log != null && log.getStatus().equals("CONFIRMED")) {
            return;  // 幂等：已确认，直接返回
        }
        
        Account account = accountRepository.findByUserId(userId);
        // 扣减冻结金额
        account.setFrozenAmount(
            account.getFrozenAmount().subtract(amount));
        accountRepository.save(account);
        
        // 更新事务日志
        log.setStatus("CONFIRMED");
        tccTransactionLogRepository.save(log);
    }
    
    // Cancel: 取消冻结
    @Transactional
    void cancelDeduct(Long userId, BigDecimal amount, String txId) {
        TccTransactionLog log = tccTransactionLogRepository
            .findByTxId(txId);
        if (log != null && log.getStatus().equals("CANCELLED")) {
            return;  // 幂等
        }
        
        Account account = accountRepository.findByUserId(userId);
        // 解冻金额
        account.setFrozenAmount(
            account.getFrozenAmount().subtract(amount));
        account.setAvailableBalance(
            account.getAvailableBalance().add(amount));
        accountRepository.save(account);
        
        log.setStatus("CANCELLED");
        tccTransactionLogRepository.save(log);
    }
}
```

### 4.3 TCC 的挑战

- **业务侵入性强**：每个操作都要实现 Try/Confirm/Cancel 三个方法
- **空回滚**：Try 未执行但收到了 Cancel 请求
- **幂等性**：Confirm/Cancel 必须幂等
- **悬挂**：Cancel 比 Try 先执行

---

## 5. Saga 模式

### 5.1 原理

Saga 将长事务拆分为多个本地事务，每个本地事务有对应的补偿操作：

```
正向流程（成功）：
[创建订单] → [扣减库存] → [扣减余额] → [确认订单] → ✅ 完成

补偿流程（任一步骤失败）：
[创建订单] → [扣减库存] → [扣减余额] ✗ 余额不足！
                         ← [恢复库存] ← [取消订单] ← 补偿回滚
```

### 5.2 两种 Saga 协调方式

**编排式（Choreography）- 事件驱动：**

```
Order Service 发布 OrderCreated 事件
    → Inventory Service 监听 → 扣减库存 → 发布 StockDeducted 事件
        → Account Service 监听 → 扣减余额 → 发布 PaymentCompleted 事件
            → Order Service 监听 → 确认订单

任何一步失败 → 发布补偿事件 → 上游服务监听补偿
```

**控制式（Orchestration）- 集中协调：**

```
┌────────────────────────┐
│     Saga Orchestrator  │
│                        │
│ 1. → 创建订单          │
│ 2. → 扣减库存          │
│ 3. → 扣减余额          │
│ 4. → 确认订单          │
│                        │
│ 失败时反向补偿：        │
│ 3. ← 恢复余额          │
│ 2. ← 恢复库存          │
│ 1. ← 取消订单          │
└────────────────────────┘
```

### 5.3 Saga 代码示例（编排式）

```java
@Service
@Slf4j
public class OrderSagaOrchestrator {

    @Autowired
    private OrderClient orderClient;
    @Autowired
    private InventoryClient inventoryClient;
    @Autowired
    private AccountClient accountClient;

    public void executeCreateOrderSaga(OrderRequest request) {
        String sagaId = UUID.randomUUID().toString();
        
        try {
            // Step 1: 创建订单
            Order order = orderClient.createOrder(request);
            log.info("[Saga:{}] Order created: {}", sagaId, order.getId());

            try {
                // Step 2: 扣减库存
                inventoryClient.deductStock(
                    request.getProductId(), request.getQuantity());
                log.info("[Saga:{}] Stock deducted", sagaId);

                try {
                    // Step 3: 扣减余额
                    accountClient.deductBalance(
                        request.getUserId(), order.getTotalPrice());
                    log.info("[Saga:{}] Balance deducted", sagaId);

                    // Step 4: 确认订单
                    orderClient.confirmOrder(order.getId());
                    log.info("[Saga:{}] Order confirmed", sagaId);

                } catch (Exception e) {
                    // Step 3 失败，补偿 Step 2
                    log.error("[Saga:{}] Balance deduction failed, compensating...", sagaId);
                    inventoryClient.restoreStock(
                        request.getProductId(), request.getQuantity());
                    throw e;
                }

            } catch (Exception e) {
                // Step 2 失败，补偿 Step 1
                log.error("[Saga:{}] Stock deduction failed, compensating...", sagaId);
                orderClient.cancelOrder(order.getId());
                throw e;
            }

        } catch (Exception e) {
            log.error("[Saga:{}] Saga failed: {}", sagaId, e.getMessage());
            throw new BusinessException("订单创建失败: " + e.getMessage());
        }
    }
}
```

---

## 6. 本地消息表

### 6.1 原理

利用本地事务 + 消息表保证最终一致性：

```
1. 业务操作和写消息表在同一个本地事务中
2. 后台线程轮询消息表，发送消息到 MQ
3. 消费者消费消息，处理业务
4. 处理成功后确认消息
```

### 6.2 代码示例

```java
@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Transactional  // 同一个本地事务
    public Order createOrder(OrderRequest request) {
        // 1. 业务操作：创建订单
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setProductId(request.getProductId());
        order.setStatus("CREATED");
        orderRepository.save(order);

        // 2. 写入消息表（同一个事务）
        OutboxMessage message = new OutboxMessage();
        message.setAggregateType("ORDER");
        message.setAggregateId(order.getId().toString());
        message.setEventType("ORDER_CREATED");
        message.setPayload(JsonUtils.toJson(order));
        message.setStatus("PENDING");
        outboxRepository.save(message);

        return order;
    }
}

// 消息投递器（定时任务）
@Component
@Slf4j
public class OutboxMessageSender {

    @Autowired
    private OutboxMessageRepository outboxRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 1000)  // 每秒执行一次
    @Transactional
    public void sendPendingMessages() {
        List<OutboxMessage> messages = outboxRepository
            .findByStatus("PENDING");

        for (OutboxMessage message : messages) {
            try {
                rabbitTemplate.convertAndSend(
                    message.getAggregateType() + ".exchange",
                    message.getEventType(),
                    message.getPayload()
                );
                message.setStatus("SENT");
                outboxRepository.save(message);
            } catch (Exception e) {
                log.error("Failed to send message: {}", message.getId(), e);
                // 下次重试
            }
        }
    }
}
```

---

## 7. 实战：Seata

### 7.1 Seata 简介

Seata 是阿里巴巴开源的分布式事务框架，支持 AT、TCC、Saga、XA 四种模式。

### 7.2 AT 模式（推荐入门）

AT 模式是最简单的，对业务无侵入：

```
一阶段：
1. 拦截 SQL，解析语义
2. 查询修改前数据，生成前镜像（Before Image）
3. 执行业务 SQL
4. 查询修改后数据，生成后镜像（After Image）
5. 生成回滚日志（Undo Log），保存到数据库
6. 提交本地事务

二阶段（提交）：
1. 删除 Undo Log（异步）

二阶段（回滚）：
1. 根据 Undo Log 反向补偿
```

### 7.3 依赖与配置

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
</dependency>
```

```yaml
seata:
  enabled: true
  application-id: order-service
  tx-service-group: my-test-group
  service:
    vgroup-mapping:
      my-test-group: default
  registry:
    type: nacos
    nacos:
      server-addr: localhost:8848
      namespace: seata
```

### 7.4 使用

```java
@Service
public class OrderService {

    @GlobalTransactional(name = "create-order", rollbackFor = Exception.class)
    public Order createOrder(OrderRequest request) {
        // 1. 创建订单
        Order order = orderRepository.save(new Order(request));
        
        // 2. 远程调用扣减库存
        inventoryClient.deduct(request.getProductId(), request.getQuantity());
        
        // 3. 远程调用扣减余额
        accountClient.deduct(request.getUserId(), order.getTotalPrice());
        
        // 任何一步抛异常 → Seata 自动回滚所有操作！
        return order;
    }
}
```

---

## 8. 方案选择指南

```
需要强一致性？
├── 是 → 2PC/XA（但性能差，慎重选择）
└── 否 → 最终一致性即可
         ├── 愿意写补偿逻辑？
         │   ├── 是 → Saga（长流程） / TCC（短流程、资金类）
         │   └── 否 → Seata AT（最简单，推荐入门）
         └── 已有消息队列？
             ├── 是 → 本地消息表 / 事务消息
             └── 否 → Seata AT
```

---

**上一篇：** [08 - 分布式追踪](./08-distributed-tracing.md)  
**下一篇：** [10 - 容器化与编排](./10-containerization-k8s.md)
