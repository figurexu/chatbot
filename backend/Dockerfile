# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
# 阿里云 Maven 镜像加速依赖下载
COPY settings.xml /root/.m2/settings.xml
WORKDIR /app
# 先复制 pom 预下载依赖（利用层缓存）
COPY pom.xml .
RUN mvn -q dependency:go-offline -B || true
COPY src ./src
RUN mvn -q package -DskipTests -B

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/chatbot.jar app.jar
ENV APP_VERSION=1.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
