# 微服务系列 11 - 持续集成与部署（CI/CD）

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 为什么微服务需要 CI/CD？

微服务数量多，手动部署不现实：

```
手动部署的噩梦：
- 6 个服务 × 3 个环境 = 18 次部署
- 每次部署 30 分钟 = 9 小时
- 容易出错、遗漏步骤
- 回滚困难

CI/CD 后：
- 代码提交 → 自动构建 → 自动测试 → 自动部署
- 全程可追溯、可回滚
- 分钟级完成
```

---

## 2. CI/CD 核心概念

```
持续集成（CI - Continuous Integration）
├── 代码提交 → 自动构建 → 自动测试
└── 目标：尽早发现问题

持续交付（CD - Continuous Delivery）
├── CI 通过 → 自动部署到测试环境
└── 生产部署需要手动确认

持续部署（CD - Continuous Deployment）
├── CI 通过 → 自动部署到所有环境（包括生产）
└── 全自动，无需人工干预

完整流程：
代码提交 → 构建 → 单元测试 → 集成测试 → 构建镜像 → 部署测试环境
    → 验收测试 → 部署预发 → 部署生产 → 监控验证
```

---

## 3. GitLab CI 实战

### 3.1 Pipeline 配置

```yaml
# .gitlab-ci.yml

stages:
  - build
  - test
  - package
  - deploy

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=.m2/repository"
  DOCKER_REGISTRY: "registry.example.com"

# 缓存 Maven 依赖
cache:
  paths:
    - .m2/repository/

# ==================== 构建阶段 ====================
build:
  stage: build
  image: eclipse-temurin:21-jdk
  script:
    - mvn clean compile -DskipTests
  artifacts:
    paths:
      - "**/target/"
    expire_in: 1 hour

# ==================== 测试阶段 ====================
unit-test:
  stage: test
  image: eclipse-temurin:21-jdk
  script:
    - mvn test
  artifacts:
    reports:
      junit:
        - "**/target/surefire-reports/TEST-*.xml"

integration-test:
  stage: test
  image: eclipse-temurin:21-jdk
  services:
    - mysql:8.0
  variables:
    MYSQL_ROOT_PASSWORD: test
    MYSQL_DATABASE: test_db
  script:
    - mvn verify -P integration-test

# ==================== 打包阶段 ====================
docker-build:
  stage: package
  image: docker:24
  services:
    - docker:24-dind
  script:
    - echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USER" --password-stdin $DOCKER_REGISTRY
    - |
      for service in user-service order-service product-service api-gateway eureka-server; do
        docker build -t $DOCKER_REGISTRY/$service:$CI_COMMIT_SHA -f $service/Dockerfile $service/
        docker push $DOCKER_REGISTRY/$service:$CI_COMMIT_SHA
        docker tag $DOCKER_REGISTRY/$service:$CI_COMMIT_SHA $DOCKER_REGISTRY/$service:latest
        docker push $DOCKER_REGISTRY/$service:latest
      done
  only:
    - main
    - tags

# ==================== 部署阶段 ====================
deploy-dev:
  stage: deploy
  image: bitnami/kubectl
  script:
    - kubectl config use-context dev
    - kubectl set image deployment/user-service user-service=$DOCKER_REGISTRY/user-service:$CI_COMMIT_SHA -n dev
    - kubectl set image deployment/order-service order-service=$DOCKER_REGISTRY/order-service:$CI_COMMIT_SHA -n dev
    - kubectl set image deployment/product-service product-service=$DOCKER_REGISTRY/product-service:$CI_COMMIT_SHA -n dev
    - kubectl rollout status deployment/user-service -n dev --timeout=120s
  environment:
    name: dev
  only:
    - main

deploy-prod:
  stage: deploy
  image: bitnami/kubectl
  script:
    - kubectl config use-context prod
    - kubectl set image deployment/user-service user-service=$DOCKER_REGISTRY/user-service:$CI_COMMIT_SHA -n prod
    - kubectl rollout status deployment/user-service -n prod --timeout=300s
  environment:
    name: prod
  when: manual    # 手动触发
  only:
    - tags
```

---

## 4. GitHub Actions 实战

```yaml
# .github/workflows/ci-cd.yml

name: Microservices CI/CD

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  DOCKER_REGISTRY: registry.example.com
  JAVA_VERSION: '21'

jobs:
  # 检测哪些服务有变更
  detect-changes:
    runs-on: ubuntu-latest
    outputs:
      user-service: ${{ steps.filter.outputs.user-service }}
      order-service: ${{ steps.filter.outputs.order-service }}
      product-service: ${{ steps.filter.outputs.product-service }}
    steps:
      - uses: actions/checkout@v4
      - uses: dorny/paths-filter@v2
        id: filter
        with:
          filters: |
            user-service:
              - 'user-service/**'
            order-service:
              - 'order-service/**'
            product-service:
              - 'product-service/**'

  # 构建和测试
  build:
    needs: detect-changes
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service:
          - user-service
          - order-service
          - product-service
        exclude:
          - service: user-service
            # 仅在有变更时构建
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'
          cache: 'maven'
      - name: Build & Test
        run: mvn -pl ${{ matrix.service }} clean verify

  # 构建镜像并推送
  docker-push:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    strategy:
      matrix:
        service: [user-service, order-service, product-service]
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ${{ env.DOCKER_REGISTRY }}
          username: ${{ secrets.DOCKER_USER }}
          password: ${{ secrets.DOCKER_PASSWORD }}
      - uses: docker/build-push-action@v5
        with:
          context: ./${{ matrix.service }}
          push: true
          tags: |
            ${{ env.DOCKER_REGISTRY }}/${{ matrix.service }}:${{ github.sha }}
            ${{ env.DOCKER_REGISTRY }}/${{ matrix.service }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  # 部署到 K8s
  deploy:
    needs: docker-push
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-kubectl@v3
      - name: Deploy
        run: |
          kubectl config use-context production
          kubectl apply -f k8s/
          kubectl rollout status deployment/user-service --timeout=300s
```

---

## 5. 部署策略

### 5.1 滚动更新（Rolling Update）

```
默认策略，逐步替换旧版本：

v1 v1 v1 v1     →  v1 v1 v1 v2     →  v1 v1 v2 v2     →  v2 v2 v2 v2
                   ↑ 新版本加入        ↑ 继续替换          ↑ 全部更新

优点：无需额外资源
缺点：新旧版本同时存在一段时间
```

### 5.2 蓝绿部署（Blue/Green）

```
两套完全相同的环境，切换流量：

Blue (v1): ●●●●     ← 当前流量
Green (v2): ○○○○    ← 新版本

验证通过后：
Blue (v1): ○○○○
Green (v2): ●●●●    ← 流量切换

优点：零停机、秒级回滚
缺点：需要双倍资源
```

### 5.3 金丝雀发布（Canary）

```
逐步将流量从旧版本切换到新版本：

v1: ●●●● (100%)  →  v1: ●●●○ (75%)  →  v1: ●●○○ (50%)  →  v1: ○  →  v2: ●●●●
v2: ○○○○ (0%)    →  v2: ○○○● (25%)  →  v2: ○○●● (50%)  →  ...  →

优点：风险可控、渐进式
缺点：需要流量管理能力
```

### 5.4 K8s 金丝雀发布示例

```yaml
# 使用 Argo Rollouts 实现金丝雀发布
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: user-service
spec:
  replicas: 4
  strategy:
    canary:
      steps:
        - setWeight: 25       # 25% 流量到新版本
        - pause: {duration: 5m}  # 观察 5 分钟
        - setWeight: 50       # 50% 流量
        - pause: {duration: 5m}
        - setWeight: 75       # 75% 流量
        - pause: {duration: 5m}
        - setWeight: 100      # 全量发布
  selector:
    matchLabels:
      app: user-service
  template:
    # ... Pod 模板
```

---

## 6. CI/CD 最佳实践

1. **每次提交都触发 CI**：快速反馈
2. **测试金字塔**：单元测试 > 集成测试 > E2E 测试
3. **构建一次，到处部署**：同一个镜像走完所有环境
4. **环境一致性**：开发 = 测试 = 预发 = 生产
5. **自动化回滚**：健康检查失败自动回滚
6. **变更检测**：只构建有变更的服务
7. **制品管理**：版本化、可追溯
8. **安全扫描**：镜像漏洞扫描、依赖检查

---

**上一篇：** [10 - 容器化与编排](./10-containerization-k8s.md)  
**下一篇：** [12 - 可观测性](./12-observability.md)
