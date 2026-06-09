# ==================== 构建阶段 ====================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# 复制 Maven 相关文件
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY config-server/pom.xml config-server/pom.xml

# 下载依赖（利用 Docker 缓存）
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# 复制源码
COPY common/ common/
COPY eureka-server/ eureka-server/
COPY api-gateway/ api-gateway/
COPY user-service/ user-service/
COPY order-service/ order-service/
COPY product-service/ product-service/
COPY config-server/ config-server/

# 构建
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -B

# ==================== 运行阶段 ====================
# 默认服务名称，构建时通过 --build-arg 覆盖
ARG SERVICE_NAME=user-service

FROM eclipse-temurin:21-jre-jammy

RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app

COPY --from=builder /app/${SERVICE_NAME}/target/*.jar app.jar

RUN chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
