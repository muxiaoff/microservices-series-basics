# 微服务系列 12 - 可观测性

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 什么是可观测性？

可观测性（Observability）是通过系统外部输出推断系统内部状态的能力。

### 1.1 可观测性三支柱

```
┌─────────────────────────────────────────────────┐
│                  可观测性                         │
│                                                   │
│  ┌─────────┐    ┌──────────┐    ┌─────────────┐  │
│  │  日志    │    │  指标     │    │  追踪        │  │
│  │ (Logs)  │    │(Metrics) │    │ (Traces)    │  │
│  │         │    │          │    │             │  │
│  │ 离散事件│    │ 聚合数据  │    │ 请求链路     │  │
│  │ 排查问题│    │ 监控趋势  │    │ 定位瓶颈     │  │
│  │ 审计    │    │ 告警     │    │ 依赖分析     │  │
│  └─────────┘    └──────────┘    └─────────────┘  │
└─────────────────────────────────────────────────┘
```

| 支柱 | 关注问题 | 类比 |
|------|----------|------|
| **日志（Logs）** | 发生了什么？ | 飞行记录仪 |
| **指标（Metrics）** | 现在状况如何？ | 仪表盘 |
| **追踪（Traces）** | 请求经历了什么？ | GPS 轨迹 |

---

## 2. 日志（Logs）

### 2.1 日志最佳实践

```java
@Slf4j
@RestController
public class OrderController {

    // ✅ 好的日志：结构化、包含关键信息
    @PostMapping("/orders")
    public Order createOrder(@RequestBody OrderRequest request) {
        log.info("Creating order: userId={}, productId={}, quantity={}", 
            request.getUserId(), request.getProductId(), request.getQuantity());
        
        Order order = orderService.createOrder(request);
        
        log.info("Order created: orderId={}, status={}", 
            order.getId(), order.getStatus());
        return order;
    }

    // ✅ 异常日志：包含上下文
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.error("Business error: code={}, message={}, orderId={}", 
            e.getCode(), e.getMessage(), e.getOrderId(), e);
        return ResponseEntity.status(e.getHttpStatus())
            .body(new ErrorResponse(e.getCode(), e.getMessage()));
    }
}
```

### 2.2 日志格式 - JSON 结构化

```yaml
# logback-spring.xml 配置 JSON 格式
logging:
  pattern:
    console: '{"time":"%d","level":"%p","traceId":"%X{traceId}","spanId":"%X{spanId}","service":"${spring.application.name}","logger":"%c","message":"%m"}%n'
```

输出示例：
```json
{
  "time": "2024-01-15T10:30:00.123",
  "level": "INFO",
  "traceId": "abc123",
  "spanId": "def456",
  "service": "order-service",
  "logger": "com.example.order.OrderService",
  "message": "Order created: orderId=10086"
}
```

### 2.3 ELK 技术栈

```
┌──────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────┐
│  应用     │───▶│  Logstash    │───▶│Elasticsearch │───▶│ Kibana   │
│  日志输出  │    │  (收集+转换)  │    │  (存储+搜索)  │    │ (可视化) │
└──────────┘    └──────────────┘    └──────────────┘    └──────────┘

或使用轻量级替代方案：
┌──────────┐    ┌──────────────┐    ┌──────────────┐
│  应用     │───▶│   Fluentd/   │───▶│Elasticsearch │───▶ Kibana
│  日志输出  │    │  Fluent Bit  │    │              │
└──────────┘    └──────────────┘    └──────────────┘
```

### 2.4 Docker 日志收集

```yaml
# Fluentd DaemonSet 收集 K8s Pod 日志
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluentd-config
data:
  fluent.conf: |
    <source>
      @type tail
      path /var/log/containers/*.log
      pos_file /var/log/fluentd-containers.log.pos
      tag kubernetes.*
      read_from_head true
      <parse>
        @type json
        time_key time
        time_format %Y-%m-%dT%H:%M:%S.%NZ
      </parse>
    </source>

    <match **>
      @type elasticsearch
      host elasticsearch
      port 9200
      logstash_format true
    </match>
```

---

## 3. 指标（Metrics）

### 3.1 Micrometer + Prometheus + Grafana

```
┌──────────┐  暴露指标  ┌──────────────┐  抓取  ┌──────────┐  查询  ┌──────────┐
│  应用     │─────────▶│ /actuator/   │◀──────│Prometheus│◀──────│ Grafana  │
│Micrometer│  /metrics │  prometheus  │       │ (存储)   │       │ (展示)   │
└──────────┘           └──────────────┘       └──────────┘       └──────────┘
```

### 3.2 Spring Boot Actuator + Micrometer

**依赖：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**配置：**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    tags:
      application: ${spring.application.name}
    export:
      prometheus:
        enabled: true
```

### 3.3 自定义指标

```java
@Service
public class OrderService {

    private final Counter orderCreatedCounter;
    private final Timer orderCreationTimer;
    private final Gauge activeOrdersGauge;

    public OrderService(MeterRegistry registry, OrderRepository orderRepo) {
        // 计数器：记录创建的订单数
        this.orderCreatedCounter = Counter.builder("orders.created.total")
            .description("Total number of orders created")
            .tag("service", "order-service")
            .register(registry);

        // 计时器：记录订单创建耗时
        this.orderCreationTimer = Timer.builder("orders.creation.duration")
            .description("Order creation duration")
            .tag("service", "order-service")
            .register(registry);

        // 仪表：当前活跃订单数
        this.activeOrdersGauge = Gauge.builder("orders.active", 
                orderRepo, repo -> repo.countByStatus("ACTIVE"))
            .description("Number of active orders")
            .register(registry);
    }

    public Order createOrder(OrderRequest request) {
        return orderCreationTimer.record(() -> {
            Order order = processOrder(request);
            orderCreatedCounter.increment();
            return order;
        });
    }
}
```

### 3.4 Prometheus 配置

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'user-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['user-service:8081']

  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['order-service:8082']

  - job_name: 'product-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['product-service:8083']
```

### 3.5 K8s 服务发现

```yaml
# Prometheus 自动发现 K8s 服务
scrape_configs:
  - job_name: 'kubernetes-pods'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
```

### 3.6 告警规则

```yaml
# alert-rules.yml
groups:
  - name: microservices-alerts
    rules:
      - alert: HighErrorRate
        expr: |
          rate(http_server_requests_seconds_count{status=~"5.."}[5m]) 
          / rate(http_server_requests_seconds_count[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High error rate on {{ $labels.application }}"
          description: "Error rate is {{ $value | humanizePercentage }}"

      - alert: HighLatency
        expr: |
          histogram_quantile(0.95, 
            rate(http_server_requests_seconds_bucket[5m])) > 3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High latency on {{ $labels.application }}"

      - alert: PodDown
        expr: up == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Pod {{ $labels.instance }} is down"
```

---

## 4. Grafana 仪表盘

### 4.1 关键监控指标

| 类别 | 指标 | 说明 |
|------|------|------|
| **RED** | Rate | 请求速率 |
| | Errors | 错误率 |
| | Duration | 响应时间 |
| **USE** | Utilization | 资源使用率 |
| | Saturation | 资源饱和度 |
| | Errors | 错误数 |
| **业务** | 订单创建数 | 业务量 |
| | 支付成功率 | 核心指标 |
| | 库存告警 | 业务告警 |

### 4.2 Spring Boot Dashboard 核心面板

```
1. JVM 面板
   - 堆内存使用率
   - GC 次数和耗时
   - 线程数

2. HTTP 面板
   - QPS（每秒请求数）
   - 错误率（4xx/5xx）
   - P50/P95/P99 延迟

3. 系统面板
   - CPU 使用率
   - 内存使用率
   - 网络流量

4. 业务面板
   - 订单创建量
   - 支付成功率
   - 库存告警
```

---

## 5. 可观测性最佳实践

1. **日志结构化**：使用 JSON 格式，便于搜索和分析
2. **关联 Trace ID**：日志中包含 Trace ID，便于跨服务排查
3. **指标先行**：用指标做告警，用日志做排查
4. **RED 原则**：监控 Rate、Errors、Duration
5. **分级告警**：P0/P1/P2/P3 不同处理方式
6. **告警治理**：避免告警疲劳，定期审查告警规则
7. **SLO/SLI**：定义服务水平目标和指标

---

**上一篇：** [11 - 持续集成与部署](./11-ci-cd.md)  
**下一篇：** [13 - 微服务安全](./13-security.md)
