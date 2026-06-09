# 微服务从小白到精通 - 系列文章

> 从零开始，系统学习微服务架构设计与实现

## 系列目录

### 入门篇
1. [微服务概述：从单体到微服务的演进](./01-introduction.md)
2. [架构设计：如何拆分微服务](./02-architecture-design.md)

### 核心篇
3. [服务注册与发现](./03-service-registration-discovery.md)
4. [API 网关](./04-api-gateway.md)
5. [服务间通信](./05-service-communication.md)
6. [配置管理](./06-configuration-management.md)

### 可靠性篇
7. [熔断与容错](./07-circuit-breaker-resilience.md)
8. [分布式追踪](./08-distributed-tracing.md)
9. [分布式事务](./09-distributed-transaction.md)

### 运维篇
10. [容器化与编排](./10-containerization-k8s.md)
11. [持续集成与部署](./11-ci-cd.md)
12. [可观测性](./12-observability.md)

### 高级篇
13. [微服务安全](./13-security.md)
14. [最佳实践与总结](./14-best-practices.md)

### 进阶篇
15. [服务网格与 Istio](./15-service-mesh.md)
16. [事件驱动架构](./16-event-driven-architecture.md)
17. [API 设计与版本化](./17-api-design-versioning.md)
18. [微服务测试策略](./18-testing-microservices.md)
19. [数据库模式：CQRS、Event Sourcing 与分片](./19-database-patterns.md)
20. [BFF 模式与 API 聚合](./20-bff-api-aggregation.md)
21. [云原生设计模式](./21-cloud-native-patterns.md)
22. [生产就绪检查清单](./22-production-readiness.md)

### 专题篇
23. [多租户架构](./23-multi-tenancy.md)
24. [微服务性能优化](./24-performance-optimization.md)
25. [微服务治理](./25-service-governance.md)

## 配套代码

本系列文章配套的代码示例项目位于项目根目录，结构如下：

```
microservices-series-basics/
├── docs/                    # 系列文章
├── eureka-server/           # 服务注册中心
├── api-gateway/             # API 网关
├── user-service/            # 用户服务
├── order-service/           # 订单服务
├── product-service/         # 商品服务
├── config-server/           # 配置中心
├── common/                  # 公共模块
├── docker/                  # Docker 部署文件
├── k8s/                     # Kubernetes 部署文件
└── pom.xml                  # 父 POM
```

## 技术栈

- Java 21 + Spring Boot 3.5+
- Spring Cloud 2025.x
- Spring Cloud Alibaba
- Docker + Kubernetes
- MySQL + Redis + RabbitMQ
