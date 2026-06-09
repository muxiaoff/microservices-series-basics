# 微服务系列 08 - 分布式追踪

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么需要分布式追踪？

一个请求可能跨越多个微服务，当出现问题时：

```
用户反馈："下单很慢！"

请求链路：
Client → Gateway → Order Service → User Service
                                → Product Service
                                → Inventory Service
                                → Payment Service

哪个服务慢？哪个调用出了问题？没有追踪，就像大海捞针。
```

分布式追踪让每个请求的完整调用链路可视化：

```
Trace ID: abc123

[Gateway]          0ms ───────────────────────────────── 200ms
  └─[Order Svc]    5ms ──────────────────────────────── 195ms
      ├─[User Svc]  10ms ────── 30ms     ✅ 快
      ├─[Product]   35ms ────────────────── 185ms     ⚠️ 慢！
      └─[Inventory] 35ms ──── 45ms        ✅ 快

结论：Product Service 响应慢，需要优化
```

---

## 2. 分布式追踪核心概念

### 2.1 OpenTelemetry 标准

OpenTelemetry 是 CNCF 的可观测性标准，合并了 OpenTracing 和 OpenCensus：

| 概念 | 说明 | 类比 |
|------|------|------|
| **Trace** | 一次完整请求的追踪 | 一次快递的全流程 |
| **Span** | Trace 中的一个操作 | 快递的一个环节（揽收→分拣→运输→派送） |
| **SpanContext** | Span 的上下文信息 | 快递单号 |
| **Trace ID** | 全局唯一的追踪 ID | 快递总单号 |
| **Span ID** | 单个操作的唯一 ID | 每个环节的编号 |

### 2.2 追踪数据传播

```
┌──────────┐     HTTP Header        ┌──────────┐
│ Service A │───────────────────────▶│ Service B │
│           │  traceparent:          │           │
│ Trace: abc│  00-abc123-def456-01   │ Trace: abc│
│ Span: 001 │                        │ Span: 002 │
│           │◀───────────────────────│ Parent:001│
│           │  返回结果               │           │
└──────────┘                        └──────────┘

W3C Trace Context 格式：
traceparent: {version}-{trace-id}-{parent-id}-{trace-flags}
例：00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

---

## 3. 实战：Micrometer Tracing + Zipkin

Spring Cloud 2023+ 使用 Micrometer Tracing 替代了 Spring Cloud Sleuth。

### 3.1 依赖

```xml
<!-- Micrometer Tracing + Zipkin -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-sender-web</artifactId>
</dependency>
```

### 3.2 配置

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # 采样率（1.0 = 100%，生产建议 0.1）
    propagation:
      type: w3c           # 使用 W3C 标准传播
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

### 3.3 启动 Zipkin

```bash
# Docker 启动 Zipkin
docker run -d -p 9411:9411 \
  -e STORAGE_TYPE=mem \
  openzipkin/zipkin

# 或使用 Elasticsearch 存储
docker run -d -p 9411:9411 \
  -e STORAGE_TYPE=elasticsearch \
  -e ES_HOSTS=http://elasticsearch:9200 \
  openzipkin/zipkin
```

### 3.4 自定义 Span

```java
@Service
@Slf4j
public class OrderService {

    @Autowired
    private Tracer tracer;  // Micrometer Tracer

    public Order createOrder(OrderRequest request) {
        // 创建自定义 Span
        Span span = tracer.nextSpan().name("validate-order").start();
        
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("order.userId", request.getUserId().toString());
            span.tag("order.productId", request.getProductId().toString());
            
            // 业务逻辑
            validateOrder(request);
            Order order = processOrder(request);
            
            span.event("order-created");
            return order;
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### 3.5 在日志中关联 Trace ID

```xml
<!-- logback-spring.xml -->
<pattern>
    %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId},%X{spanId}] %-5level %logger{36} - %msg%n
</pattern>
```

日志输出效果：

```
2024-01-15 10:30:00.123 [http-nio-8082-exec-1] [abc123,def456] INFO  c.e.order.OrderService - Creating order
2024-01-15 10:30:00.456 [http-nio-8082-exec-1] [abc123,def456] INFO  c.e.order.OrderService - Order created: 10086
```

---

## 4. 实战：SkyWalking

### 4.1 SkyWalking 简介

SkyWalking 是国产 APM 系统，功能比 Zipkin 更强大：

| 特性 | Zipkin | SkyWalking |
|------|--------|-----------|
| **追踪** | ✅ | ✅ |
| **指标** | ❌ | ✅ |
| **告警** | ❌ | ✅ |
| **拓扑图** | ❌ | ✅ |
| **Java Agent** | ❌ | ✅（无侵入） |
| **存储** | 内存/ES/MySQL | ES/H2/MySQL |

### 4.2 使用 Java Agent（无侵入方式）

```bash
# 下载 SkyWalking Agent
# 启动时添加 Agent 参数
java -javaagent:skywalking-agent.jar \
     -Dskywalking.agent.service_name=order-service \
     -Dskywalking.collector.backend_service=localhost:11800 \
     -jar order-service.jar
```

### 4.3 Docker Compose 启动 SkyWalking

```yaml
version: '3'
services:
  elasticsearch:
    image: elasticsearch:8.12.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
    ports:
      - "9200:9200"

  skywalking-oap:
    image: apache/skywalking-oap-server:9.7.0
    environment:
      - SW_STORAGE=elasticsearch
      - SW_STORAGE_ES_CLUSTER_NODES=elasticsearch:9200
    ports:
      - "11800:11800"
      - "12800:12800"
    depends_on:
      - elasticsearch

  skywalking-ui:
    image: apache/skywalking-ui:9.7.0
    environment:
      - SW_OAP_ADDRESS=http://skywalking-oap:12800
    ports:
      - "8080:8080"
    depends_on:
      - skywalking-oap
```

---

## 5. 追踪数据采样策略

### 5.1 为什么需要采样？

100% 采集的代价太高：
- 大量追踪数据占用存储
- 网络带宽消耗
- 追踪系统自身性能开销

### 5.2 采样策略

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| **固定比例采样** | 按百分比随机采样 | 一般场景 |
| **基于速率采样** | 每秒最多采样 N 条 | 高流量场景 |
| **自适应采样** | 根据系统负载动态调整 | 智能场景 |
| **尾部采样** | 先收集全部，事后决定保留哪些 | 需要保留错误/慢请求 |

```yaml
# 固定比例采样
management:
  tracing:
    sampling:
      probability: 0.1    # 10% 采样率
```

---

## 6. 追踪最佳实践

1. **所有服务接入追踪**：否则链路会断裂
2. **传播 Trace Context**：确保跨服务传播 Trace ID
3. **合理采样**：开发 100%，生产 10% 或自适应
4. **关键业务添加自定义 Span**：帮助定位问题
5. **日志关联 Trace ID**：便于从日志跳转到追踪
6. **设置告警**：慢请求、错误率告警
7. **定期清理数据**：追踪数据有保留周期

---

**上一篇：** [07 - 熔断与容错](./07-circuit-breaker-resilience.md)  
**下一篇：** [09 - 分布式事务](./09-distributed-transaction.md)
