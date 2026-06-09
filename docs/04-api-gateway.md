# 微服务系列 04 - API 网关

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么需要 API 网关？

没有网关时，客户端需要直接和每个微服务通信：

```
❌ 没有网关：
┌────────┐  ──▶  User Service    (8081)
│        │  ──▶  Order Service   (8082)
│ Client │  ──▶  Product Service (8083)
│        │  ──▶  Payment Service (8084)
└────────┘

问题：
- 客户端需要知道每个服务的地址
- 跨域问题
- 每个服务都要实现鉴权
- 无法统一限流
- 服务重构影响客户端
```

```
✅ 有网关：
┌────────┐      ┌──────────┐      ┌───────────┐
│        │─────▶│ API      │─────▶│User Svc   │
│ Client │      │ Gateway  │─────▶│Order Svc  │
│        │      │ (8080)   │─────▶│Product Svc│
└────────┘      └──────────┘      └───────────┘

优势：
- 统一入口
- 统一鉴权
- 统一限流
- 路由转发
- 协议转换
```

---

## 2. API 网关的核心功能

| 功能 | 说明 |
|------|------|
| **路由转发** | 根据请求路径/头部将请求转发到对应服务 |
| **负载均衡** | 将请求分发到服务的多个实例 |
| **身份认证** | 统一 JWT/OAuth2 鉴权 |
| **限流熔断** | 保护后端服务不被过多请求压垮 |
| **请求过滤** | 请求头修改、参数校验 |
| **响应转换** | 数据聚合、格式转换 |
| **日志监控** | 统一请求日志和监控指标 |
| **协议转换** | HTTP ↔ gRPC、WebSocket 代理 |
| **灰度发布** | 按比例/规则路由到不同版本 |

---

## 3. 网关技术选型

| 网关 | 语言 | 特点 | 适用场景 |
|------|------|------|----------|
| **Spring Cloud Gateway** | Java | Spring 生态原生、响应式 | Spring Cloud 项目 |
| **Kong** | Lua/OpenResty | 高性能、插件丰富 | 通用 API 网关 |
| **Nginx** | C | 极致性能、反向代理 | 静态路由、负载均衡 |
| **Envoy** | C++ | 云原生、xDS 协议 | Service Mesh |
| **APISIX** | Lua/OpenResty | 高性能、插件化 | 国产、活跃社区 |
| **Traefik** | Go | 自动服务发现 | Docker/K8s 环境 |

本系列使用 **Spring Cloud Gateway**。

---

## 4. 实战：Spring Cloud Gateway

### 4.1 核心概念

```
Route（路由）= Predicate（断言）+ Filter（过滤器）+ Target URI

请求进入 Gateway
    │
    ▼
┌─────────────────────────────────┐
│  Predicate 匹配？                │
│  Path=/api/users/**             │──No──▶ 尝试下一个 Route
│  Method=GET                     │
└──────────┬──────────────────────┘
           │ Yes
           ▼
┌─────────────────────────────────┐
│  Pre Filter（前置过滤器）         │
│  - 添加请求头                    │
│  - 鉴权检查                     │
│  - 限流                        │
└──────────┬──────────────────────┘
           │
           ▼
┌─────────────────────────────────┐
│  转发到目标服务                   │
│  lb://user-service/api/users/1  │
└──────────┬──────────────────────┘
           │
           ▼
┌─────────────────────────────────┐
│  Post Filter（后置过滤器）        │
│  - 添加响应头                    │
│  - 记录日志                     │
└─────────────────────────────────┘
```

### 4.2 创建 Gateway 项目

**依赖 pom.xml：**

```xml
<dependencies>
    <!-- Spring Cloud Gateway -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>
    
    <!-- Eureka Client（用于服务发现） -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    
    <!-- LoadBalancer -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-loadbalancer</artifactId>
    </dependency>
</dependencies>
```

**启动类：**

```java
package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

### 4.3 配置路由

**方式一：YAML 配置（推荐）**

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        # 用户服务路由
        - id: user-service-route
          uri: lb://user-service        # lb:// 表示从 Eureka 获取实例 + 负载均衡
          predicates:
            - Path=/api/users/**        # 路径匹配
          filters:
            - StripPrefix=1             # 去掉路径前缀 /api
            
        # 订单服务路由
        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
          filters:
            - StripPrefix=1
            
        # 商品服务路由
        - id: product-service-route
          uri: lb://product-service
          predicates:
            - Path=/api/products/**
          filters:
            - StripPrefix=1

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

**方式二：Java 配置**

```java
@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
            .route("user-service-route", r -> r
                .path("/api/users/**")
                .filters(f -> f.stripPrefix(1))
                .uri("lb://user-service"))
            .route("order-service-route", r -> r
                .path("/api/orders/**")
                .filters(f -> f.stripPrefix(1))
                .uri("lb://order-service"))
            .route("product-service-route", r -> r
                .path("/api/products/**")
                .filters(f -> f.stripPrefix(1))
                .uri("lb://product-service"))
            .build();
    }
}
```

---

## 5. 断言（Predicates）

Spring Cloud Gateway 内置了丰富的断言工厂：

| 断言 | 说明 | 示例 |
|------|------|------|
| **Path** | 路径匹配 | `Path=/api/users/**` |
| **Method** | HTTP 方法 | `Method=GET,POST` |
| **Header** | 请求头 | `Header=X-Token, \d+` |
| **Query** | 查询参数 | `Query=name, abc` |
| **Host** | 主机名 | `Host=**.example.com` |
| **After** | 时间之后 | `After=2024-01-01T00:00:00+08:00[Asia/Shanghai]` |
| **Before** | 时间之前 | `Before=2024-12-31T23:59:59+08:00[Asia/Shanghai]` |
| **Between** | 时间区间 | `Between=时间1, 时间2` |
| **Cookie** | Cookie | `Cookie=session, abc` |
| **Weight** | 权重 | `Weight=group1, 80` |

**组合使用示例：**

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-get-route
          uri: lb://user-service
          predicates:
            - Path=/api/users/**
            - Method=GET        # 只匹配 GET 请求
            - Header=X-Token    # 必须有 X-Token 头
```

---

## 6. 过滤器（Filters）

### 6.1 过滤器分类

```
Gateway Filter
├── Pre Filter（前置）- 请求转发前执行
├── Post Filter（后置）- 收到响应后执行
└── Global Filter（全局）- 所有路由都生效
```

### 6.2 内置过滤器

| 过滤器 | 说明 | 示例 |
|--------|------|------|
| **AddRequestHeader** | 添加请求头 | `AddRequestHeader=X-Source, gateway` |
| **AddRequestParameter** | 添加请求参数 | `AddRequestParameter=flag, 1` |
| **AddResponseHeader** | 添加响应头 | `AddResponseHeader=X-Response-Foo, Bar` |
| **StripPrefix** | 去掉路径前缀 | `StripPrefix=1` |
| **PrefixPath** | 添加路径前缀 | `PrefixPath=/api` |
| **RequestRateLimiter** | 限流 | 配合 Redis 使用 |
| **CircuitBreaker** | 熔断 | 配合 Resilience4j 使用 |
| **Retry** | 重试 | `Retry=3` |

### 6.3 自定义全局过滤器

**鉴权过滤器示例：**

```java
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        
        // 白名单路径不需要鉴权
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        String token = extractToken(exchange);
        
        if (token == null || !validateToken(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders()
                .add("Content-Type", "application/json;charset=UTF-8");
            
            String body = "{\"code\":401,\"message\":\"未授权，请先登录\"}";
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        // 将用户信息传递给下游服务
        String userId = getUserIdFromToken(token);
        ServerHttpRequest request = exchange.getRequest().mutate()
            .header("X-User-Id", userId)
            .build();
        
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -1;  // 优先级最高
    }

    private boolean isWhiteListed(String path) {
        return path.startsWith("/api/auth/login")
            || path.startsWith("/api/auth/register");
    }

    private String extractToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private boolean validateToken(String token) {
        // JWT 验证逻辑
        try {
            JwtUtil.parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getUserIdFromToken(String token) {
        return JwtUtil.getUserId(token);
    }
}
```

### 6.4 限流过滤器

**基于 Redis 的请求限流：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
```

```yaml
spring:
  redis:
    host: localhost
    port: 6379
  cloud:
    gateway:
      routes:
        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10    # 每秒填充10个令牌
                redis-rate-limiter.burstCapacity: 20    # 桶容量20
                redis-rate-limiter.requestedTokens: 1   # 每次请求消耗1个令牌
                key-resolver: "#{@userKeyResolver}"     # 限流维度
```

```java
@Configuration
public class RateLimiterConfig {

    // 按用户 ID 限流
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getHeaders().getFirst("X-User-Id") 
            ?: "anonymous"
        );
    }

    // 按 IP 限流
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
        );
    }

    // 按接口路径限流
    @Bean
    public KeyResolver pathKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getPath().value()
        );
    }
}
```

---

## 7. 跨域配置

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins: "http://localhost:3000"
            allowed-methods:
              - GET
              - POST
              - PUT
              - DELETE
            allowed-headers: "*"
            allow-credentials: true
            max-age: 3600
```

或 Java 配置：

```java
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://localhost:3000");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
```

---

## 8. 网关最佳实践

### 8.1 网关应该做什么

✅ 路由转发  
✅ 身份认证与授权  
✅ 限流与熔断  
✅ 请求日志  
✅ 跨域处理  
✅ 协议转换  

### 8.2 网关不应该做什么

❌ 业务逻辑处理  
❌ 数据聚合（应该由 BFF 层处理）  
❌ 大文件上传/下载（应该绕过网关）  
❌ 长连接代理（如 WebSocket，需特殊处理）  

### 8.3 网关层架构

```
┌─────────┐
│  Client  │
└────┬─────┘
     │
┌────▼──────────────────────────┐
│        API Gateway            │
│  ┌────────┬────────┬────────┐│
│  │Auth    │Rate    │Log     ││
│  │Filter  │Limiter │Filter  ││
│  └────────┴────────┴────────┘│
│  ┌───────────────────────────┐│
│  │    Route Predicate        ││
│  └───────────────────────────┘│
└────┬──────────┬──────────┬────┘
     │          │          │
┌────▼───┐ ┌───▼────┐ ┌───▼────┐
│User Svc│ │Order   │ │Product │
│        │ │Service │ │Service │
└────────┘ └────────┘ └────────┘
```

---

**上一篇：** [03 - 服务注册与发现](./03-service-registration-discovery.md)  
**下一篇：** [05 - 服务间通信](./05-service-communication.md)
