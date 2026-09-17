# 第一阶段：构建
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
COPY src src
RUN mvn -q -DskipTests package

# 第二阶段：运行（只带 JRE 和 jar，镜像更小）
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/ipovideo-backend-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
