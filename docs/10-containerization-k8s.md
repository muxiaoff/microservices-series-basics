# 微服务系列 10 - 容器化与编排

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么需要容器化？

微服务数量多，部署环境不一致是常见问题：

```
❌ 传统部署的问题：
- 开发环境能跑，生产环境跑不起来
- 依赖版本冲突
- 部署过程不一致
- 扩缩容需要手动配置
- 环境配置文档过时

✅ 容器化后：
- 一次构建，到处运行
- 环境完全一致
- 秒级启动
- 弹性扩缩容
- 基础设施即代码
```

---

## 2. Docker 基础

### 2.1 核心概念

```
Image（镜像）→ 只读模板，包含应用和依赖
Container（容器）→ 镜像的运行实例
Dockerfile → 构建镜像的脚本
Registry → 镜像仓库（Docker Hub、Harbor）
```

### 2.2 微服务 Dockerfile 最佳实践

**多阶段构建（推荐）：**

```dockerfile
# ==================== 构建阶段 ====================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# 先复制 pom 文件，利用 Docker 缓存
COPY pom.xml .
COPY src ./src

# 构建应用（跳过测试以加速）
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests

# ==================== 运行阶段 ====================
FROM eclipse-temurin:21-jre-jammy

# 安装必要工具
RUN apt-get update && apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# 创建非 root 用户
RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app

# 从构建阶段复制 jar
COPY --from=builder /app/target/*.jar app.jar

# 修改文件所有权
RUN chown -R appuser:appuser /app

# 切换到非 root 用户
USER appuser

# 暴露端口
EXPOSE 8081

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

# 启动命令
ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
```

### 2.3 Docker Compose

**开发环境一键启动：**

```yaml
version: '3.8'

services:
  # 服务注册中心
  eureka-server:
    build:
      context: ./eureka-server
      dockerfile: Dockerfile
    ports:
      - "8761:8761"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8761/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5

  # 配置中心
  config-server:
    build:
      context: ./config-server
      dockerfile: Dockerfile
    ports:
      - "8888:8888"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
    depends_on:
      eureka-server:
        condition: service_healthy

  # API 网关
  api-gateway:
    build:
      context: ./api-gateway
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
    depends_on:
      eureka-server:
        condition: service_healthy

  # 用户服务
  user-service:
    build:
      context: ./user-service
      dockerfile: Dockerfile
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/user_db
    depends_on:
      eureka-server:
        condition: service_healthy
      mysql:
        condition: service_healthy

  # 订单服务
  order-service:
    build:
      context: ./order-service
      dockerfile: Dockerfile
    ports:
      - "8082:8082"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/order_db
    depends_on:
      eureka-server:
        condition: service_healthy
      mysql:
        condition: service_healthy

  # 商品服务
  product-service:
    build:
      context: ./product-service
      dockerfile: Dockerfile
    ports:
      - "8083:8083"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/product_db
    depends_on:
      eureka-server:
        condition: service_healthy
      mysql:
        condition: service_healthy

  # MySQL
  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: rootpassword
    volumes:
      - mysql-data:/var/lib/mysql
      - ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  mysql-data:
```

---

## 3. Kubernetes 基础

### 3.1 K8s 核心概念

```
Pod         → 最小部署单元，包含一个或多个容器
Deployment  → 管理 Pod 的副本数和更新策略
Service     → 为 Pod 提供稳定的访问入口
ConfigMap   → 存储配置数据
Secret      → 存储敏感数据
Ingress     → HTTP 路由规则（外部入口）
Namespace   → 资源隔离
```

### 3.2 微服务 K8s 部署文件

**user-service-deployment.yaml：**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
  namespace: microservices
  labels:
    app: user-service
spec:
  replicas: 2                    # 2 个副本
  selector:
    matchLabels:
      app: user-service
  strategy:
    type: RollingUpdate          # 滚动更新
    rollingUpdate:
      maxSurge: 1               # 更新时最多多出 1 个
      maxUnavailable: 0         # 更新时不允许不可用
  template:
    metadata:
      labels:
        app: user-service
    spec:
      containers:
        - name: user-service
          image: registry.example.com/user-service:1.0.0
          ports:
            - containerPort: 8081
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "k8s"
            - name: SPRING_DATASOURCE_URL
              valueFrom:
                configMapKeyRef:
                  name: user-service-config
                  key: database-url
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: user-service-secret
                  key: database-password
          resources:
            requests:
              memory: "256Mi"
              cpu: "200m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:          # 存活探针
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 60
            periodSeconds: 10
          readinessProbe:         # 就绪探针
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 5
          startupProbe:           # 启动探针
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            failureThreshold: 30
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: user-service
  namespace: microservices
spec:
  selector:
    app: user-service
  ports:
    - port: 8081
      targetPort: 8081
  type: ClusterIP
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: user-service-config
  namespace: microservices
data:
  database-url: "jdbc:mysql://mysql:3306/user_db"
  eureka-url: "http://eureka-server:8761/eureka/"
---
apiVersion: v1
kind: Secret
metadata:
  name: user-service-secret
  namespace: microservices
type: Opaque
data:
  database-password: cGFzc3dvcmQxMjM=    # base64 编码
```

### 3.3 Ingress 配置

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: microservices-ingress
  namespace: microservices
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2
spec:
  ingressClassName: nginx
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /users(/|$)(.*)
            pathType: Prefix
            backend:
              service:
                name: user-service
                port:
                  number: 8081
          - path: /orders(/|$)(.*)
            pathType: Prefix
            backend:
              service:
                name: order-service
                port:
                  number: 8082
          - path: /products(/|$)(.*)
            pathType: Prefix
            backend:
              service:
                name: product-service
                port:
                  number: 8083
```

### 3.4 HPA 自动扩缩容

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: user-service-hpa
  namespace: microservices
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: user-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

---

## 4. 容器化最佳实践

### 4.1 镜像优化

| 实践 | 说明 |
|------|------|
| **多阶段构建** | 构建和运行使用不同镜像 |
| **选择精简基础镜像** | JRE 而非 JDK，Alpine 或 Distroless |
| **利用缓存** | 先复制依赖文件，再复制源码 |
| **.dockerignore** | 排除不需要的文件 |
| **非 root 运行** | 安全最佳实践 |
| **固定版本** | 不使用 latest 标签 |

### 4.2 .dockerignore

```
.git
.idea
target
*.md
*.log
.env
docker-compose*.yml
Dockerfile
```

### 4.3 K8s 部署最佳实践

| 实践 | 说明 |
|------|------|
| **资源限制** | 必须设置 requests 和 limits |
| **健康检查** | liveness + readiness + startup 三件套 |
| **滚动更新** | maxSurge=1, maxUnavailable=0 |
| **Pod 反亲和** | 同一服务副本分散到不同节点 |
| **PDB** | 保证最小可用副本数 |
| **NetworkPolicy** | 限制 Pod 间网络访问 |

---

**上一篇：** [09 - 分布式事务](./09-distributed-transaction.md)  
**下一篇：** [11 - 持续集成与部署](./11-ci-cd.md)
