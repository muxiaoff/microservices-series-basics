# Microservices Series - From Beginner to Expert

微服务从小白到精通 - 系列文章与代码示例

## 系列文章

详见 [docs/](./docs/README.md)

| 编号 | 文章 | 主题 |
|------|------|------|
| 01 | [微服务概述](./docs/01-introduction.md) | 从单体到微服务的演进 |
| 02 | [架构设计](./docs/02-architecture-design.md) | 如何拆分微服务 |
| 03 | [服务注册与发现](./docs/03-service-registration-discovery.md) | Eureka / Nacos |
| 04 | [API 网关](./docs/04-api-gateway.md) | Spring Cloud Gateway |
| 05 | [服务间通信](./docs/05-service-communication.md) | REST / Feign / gRPC / MQ |
| 06 | [配置管理](./docs/06-configuration-management.md) | Spring Cloud Config / Nacos |
| 07 | [熔断与容错](./docs/07-circuit-breaker-resilience.md) | Resilience4j / Sentinel |
| 08 | [分布式追踪](./docs/08-distributed-tracing.md) | Zipkin / SkyWalking |
| 09 | [分布式事务](./docs/09-distributed-transaction.md) | Saga / TCC / Seata |
| 10 | [容器化与编排](./docs/10-containerization-k8s.md) | Docker / Kubernetes |
| 11 | [持续集成与部署](./docs/11-ci-cd.md) | GitLab CI / GitHub Actions |
| 12 | [可观测性](./docs/12-observability.md) | Prometheus / Grafana / ELK |
| 13 | [微服务安全](./docs/13-security.md) | JWT / OAuth2 |
| 14 | [最佳实践与总结](./docs/14-best-practices.md) | 架构全景 / 反模式 |

## 代码示例项目

基于 **Spring Boot 3.5 + Spring Cloud 2025** 的电商微服务示例：

```
microservices-series-basics/
├── docs/                    # 系列文章
├── common/                  # 公共模块（DTO、异常、工具类）
├── eureka-server/           # 服务注册中心 (8761)
├── api-gateway/             # API 网关 (8080)
├── user-service/            # 用户服务 (8081)
├── order-service/           # 订单服务 (8082)
├── product-service/         # 商品服务 (8083)
├── config-server/           # 配置中心 (8888)
├── docker/                  # Docker 部署文件
├── k8s/                     # Kubernetes 部署文件
├── sql/                     # 数据库初始化脚本
└── pom.xml                  # 父 POM
```

## 快速开始

### 本地运行

1. 启动 Eureka Server
2. 启动 User Service、Product Service
3. 启动 Order Service（依赖 User 和 Product）
4. 启动 API Gateway

### Docker Compose 运行

```bash
cd docker
docker-compose up -d
```

### Kubernetes 运行

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/
```

## 技术栈

- Java 21 + Spring Boot 3.5
- Spring Cloud 2025.x
- Spring Cloud Alibaba
- Spring Data JPA + H2 (Demo) / MySQL (Production)
- Spring Cloud Gateway
- OpenFeign
- Resilience4j
- Docker + Kubernetes
