# 微服务系列 15 - 服务网格与 Istio

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 什么是服务网格（Service Mesh）？

在前面的章节中，我们用 Spring Cloud 组件（Eureka、Gateway、Resilience4j、Sleuth）实现了服务注册发现、流量管理、熔断限流、分布式追踪等功能。这些能力都是**在应用代码中实现的**（通过 SDK、注解、拦截器）。

服务网格将这些能力从应用代码中**剥离出来**，下沉到基础设施层：

```
传统微服务（SDK 模式）：
┌─────────────────────────────────┐
│           应用代码               │
│  ┌───────────────────────────┐ │
│  │    业务逻辑                │ │
│  └───────────────────────────┘ │
│  ┌───────────────────────────┐ │
│  │  SDK（熔断/追踪/负载均衡） │ │  ← 应用需要引入 SDK
│  └───────────────────────────┘ │
└─────────────────────────────────┘

服务网格（Sidecar 模式）：
┌──────────────────┐    ┌──────────────────┐
│    应用代码       │    │     Sidecar      │
│ ┌──────────────┐ │    │ ┌──────────────┐ │
│ │  业务逻辑     │ │    │ │ 熔断/追踪     │ │  ← 基础设施透明代理
│ └──────────────┘ │    │ │ 负载均衡/加密  │ │
│                  │◀──▶│ └──────────────┘ │
└──────────────────┘    └──────────────────┘
```

### 1.1 核心概念

| 概念 | 说明 |
|------|------|
| **Sidecar** | 与应用容器同 Pod 部署的代理，拦截所有出入流量 |
| **数据面** | Sidecar 代理组成的网络，负责实际的流量转发 |
| **控制面** | 管理和配置 Sidecar 的控制组件（策略下发、证书管理等） |
| **mTLS** | 服务间自动双向 TLS 加密，无需修改应用代码 |
| **流量治理** | 按权重路由、灰度发布、故障注入等 |

### 1.2 SDK 模式 vs Service Mesh

| 维度 | SDK 模式（Spring Cloud） | Service Mesh（Istio） |
|------|--------------------------|----------------------|
| **语言绑定** | 绑定 Java | 语言无关 |
| **代码侵入** | 需要引入 SDK | 零侵入 |
| **升级** | 需要改代码重新部署 | 只升级 Sidecar |
| **运维复杂度** | 低 | 高 |
| **性能开销** | 低 | 略高（多一跳） |
| **调试** | 简单 | 较复杂 |
| **功能覆盖** | 应用层 | 网络 + 安全层 |
| **学习曲线** | 低 | 高 |

---

## 2. Istio 架构详解

Istio 是目前最流行的服务网格实现，由 Google、IBM、Lyft 联合开发。

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────┐
│                    Control Plane                     │
│                   (istiod)                           │
│  ┌────────────┐  ┌────────────┐  ┌──────────────┐  │
│  │  Pilot     │  │  Citadel   │  │  Galley      │  │
│  │ 服务发现    │  │  证书管理   │  │  配置验证     │  │
│  │ 流量管理    │  │  mTLS      │  │              │  │
│  └────────────┘  └────────────┘  └──────────────┘  │
└────────────────────────┬────────────────────────────┘
                         │ xDS API (下发配置)
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Pod A      │ │   Pod B      │ │   Pod C      │
│ ┌──────────┐ │ │ ┌──────────┐ │ │ ┌──────────┐ │
│ │  App     │ │ │ │  App     │ │ │ │  App     │ │
│ └──────────┘ │ │ └──────────┘ │ │ └──────────┘ │
│ ┌──────────┐ │ │ ┌──────────┐ │ │ ┌──────────┐ │
│ │  Envoy   │ │ │ │  Envoy   │ │ │ │  Envoy   │ │
│ │ (Sidecar)│ │ │ │ (Sidecar)│ │ │ │ (Sidecar)│ │
│ └──────────┘ │ │ └──────────┘ │ │ └──────────┘ │
└──────────────┘ └──────────────┘ └──────────────┘
       Data Plane (Envoy Proxies)
```

### 2.2 Istio 核心组件

| 组件 | 说明 |
|------|------|
| **istiod** | 控制面核心（Pilot + Citadel + Galley 合并） |
| **Envoy** | 数据面代理，以 Sidecar 方式注入每个 Pod |
| **istioctl** | 命令行工具 |
| **Istio Gateway** | 替代 K8s Ingress，管理南北向流量 |

### 2.3 Istio 资源对象

| 资源 | 作用 | 类比 |
|------|------|------|
| **VirtualService** | 路由规则、重试、超时、故障注入 | Nginx location |
| **DestinationRule** | 负载均衡、连接池、熔断、异常检测 | Upstream 配置 |
| **Gateway** | 入口网关配置 | K8s Ingress |
| **ServiceEntry** | 注册外部服务 | 外部服务白名单 |
| **PeerAuthentication** | mTLS 策略 | 安全策略 |
| **AuthorizationPolicy** | 授权策略 | 网络策略 |

---

## 3. 实战：Istio 安装与配置

### 3.1 安装 Istio

```bash
# 下载 Istio
curl -L https://istio.io/downloadIstio | sh -
cd istio-1.24.0
export PATH=$PWD/bin:$PATH

# 安装 Istio（demo 配置，包含 Kiali 等可观测性组件）
istioctl install --set profile=demo -y

# 验证安装
kubectl get pods -n istio-system
# NAME                                    READY   STATUS
# istio-egressgateway-xxxx               1/1     Running
# istio-ingressgateway-xxxx              1/1     Running
# istiod-xxxx                            1/1     Running
# kiali-xxxx                             1/1     Running
# prometheus-xxxx                        2/2     Running
```

### 3.2 Sidecar 自动注入

```bash
# 为命名空间启用自动注入
kubectl label namespace microservices istio-injection=enabled

# 之后在该命名空间创建的 Pod 会自动注入 Envoy Sidecar
kubectl get pods -n microservices
# NAME                            READY   STATUS
# user-service-xxxx               2/2     Running    ← 2/2 表示 App + Sidecar
# order-service-xxxx              2/2     Running
# product-service-xxxx            2/2     Running
```

### 3.3 Istio Gateway 配置

```yaml
# gateway.yaml
apiVersion: networking.istio.io/v1alpha3
kind: Gateway
metadata:
  name: microservices-gateway
  namespace: microservices
spec:
  selector:
    istio: ingressgateway
  servers:
    - port:
        number: 80
        name: http
        protocol: HTTP
      hosts:
        - "api.example.com"
```

### 3.4 VirtualService 路由

```yaml
# virtual-service.yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: user-service-route
  namespace: microservices
spec:
  hosts:
    - "api.example.com"
  gateways:
    - microservices-gateway
  http:
    - match:
        - uri:
            prefix: /api/users
      route:
        - destination:
            host: user-service
            port:
              number: 8081
      timeout: 10s
      retries:
        attempts: 3
        perTryTimeout: 3s
```

---

## 4. 流量治理

### 4.1 金丝雀发布（按权重路由）

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: user-service-canary
  namespace: microservices
spec:
  hosts:
    - user-service
  http:
    - route:
        - destination:
            host: user-service
            subset: v1
          weight: 90           # 90% 流量到 v1
        - destination:
            host: user-service
            subset: v2
          weight: 10           # 10% 流量到 v2
---
apiVersion: networking.istio.io/v1alpha3
kind: DestinationRule
metadata:
  name: user-service-dest
  namespace: microservices
spec:
  host: user-service
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 100
      http:
        h2UpgradePolicy: DEFAULT
        http1MaxPendingRequests: 1000
        http2MaxRequests: 1000
  subsets:
    - name: v1
      labels:
        version: v1
    - name: v2
      labels:
        version: v2
```

### 4.2 基于请求内容的路由

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: user-service-content-route
spec:
  hosts:
    - user-service
  http:
    # 内部用户路由到 v2
    - match:
        - headers:
            x-user-type:
              exact: internal
      route:
        - destination:
            host: user-service
            subset: v2
    # 其他用户路由到 v1
    - route:
        - destination:
            host: user-service
            subset: v1
```

### 4.3 故障注入（混沌测试）

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: user-service-fault-injection
spec:
  hosts:
    - user-service
  http:
    - fault:
        delay:
          percentage:
            value: 50          # 50% 的请求延迟 5 秒
          fixedDelay: 5s
      route:
        - destination:
            host: user-service
```

### 4.4 熔断（DestinationRule）

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: DestinationRule
metadata:
  name: user-service-circuit-breaker
spec:
  host: user-service
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 100
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 100
    outlierDetection:
      consecutive5xxErrors: 5         # 连续 5 次 5xx 错误
      interval: 30s                    # 检测间隔
      baseEjectionTime: 60s           # 驱逐时间
      maxEjectionPercent: 50          # 最大驱逐比例
```

---

## 5. 安全：mTLS 与授权

### 5.1 启用 mTLS

```yaml
# 全局 mTLS（严格模式）
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: istio-system
spec:
  mtls:
    mode: STRICT    # 所有服务间通信必须 mTLS
```

```yaml
# 仅特定命名空间 PERMISSIVE 模式
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: microservices-mtls
  namespace: microservices
spec:
  mtls:
    mode: PERMISSIVE   # 同时允许 mTLS 和明文
```

### 5.2 授权策略

```yaml
# 只允许 order-service 调用 user-service
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: user-service-policy
  namespace: microservices
spec:
  selector:
    matchLabels:
      app: user-service
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/microservices/sa/order-service"]
      to:
        - operation:
            methods: ["GET"]
            paths: ["/users/*"]
```

---

## 6. 可观测性：Kiali

### 6.1 Kiali 简介

Kiali 是 Istio 的管理控制台，提供服务拓扑图、流量监控、配置验证等功能。

```bash
# 访问 Kiali
istioctl dashboard kiali
```

### 6.2 Kiali 功能

| 功能 | 说明 |
|------|------|
| **服务拓扑图** | 可视化服务间调用关系和流量 |
| **流量监控** | 实时 QPS、错误率、延迟 |
| **配置验证** | 检查 Istio 配置是否正确 |
| **分布式追踪** | 集成 Jaeger 追踪 |
| **mTLS 状态** | 查看加密状态 |

---

## 7. 何时引入 Service Mesh？

### 7.1 适合引入的场景

- 服务数量多（50+），SDK 管理成本高
- 多语言技术栈（Java、Go、Python 混合）
- 需要细粒度流量治理
- 安全合规要求 mTLS
- 需要灰度发布但不想修改代码

### 7.2 暂不需要的场景

- 服务数量少（< 10 个）
- 单一技术栈（纯 Java 用 Spring Cloud 就够了）
- 团队没有 K8s 经验
- 性能敏感型应用（Sidecar 有额外延迟）

### 7.3 渐进式采用

```
阶段 1：观察模式
├── 安装 Istio
├── 启用 Sidecar 注入
└── 通过 Kiali 观察流量（不干预）

阶段 2：流量管理
├── 配置 VirtualService 路由
├── 金丝雀发布
└── 超时和重试

阶段 3：安全加固
├── 启用 mTLS
├── 配置 AuthorizationPolicy
└── 安全审计

阶段 4：高级功能
├── 故障注入测试
├── 流量镜像
└── 自定义 Envoy Filter
```

---

## 8. Istio vs 其他 Service Mesh

| 特性 | Istio | Linkerd | Consul Connect |
|------|-------|---------|----------------|
| **代理** | Envoy | linkerd2-proxy | Envoy / Built-in |
| **性能** | 中 | 高（Rust 代理） | 中 |
| **复杂度** | 高 | 低 | 中 |
| **功能丰富度** | 最丰富 | 基础够用 | 中等 |
| **多平台** | K8s + VM | 仅 K8s | K8s + VM + 本地 |
| **社区** | 最活跃 | 活跃 | 活跃 |
| **适用** | 大规模、复杂需求 | 中小规模、追求简单 | 混合环境 |

---

**上一篇：** [14 - 最佳实践与总结](./14-best-practices.md)  
**下一篇：** [16 - 事件驱动架构](./16-event-driven-architecture.md)
