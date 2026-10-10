# 1. Vue 3 빌드
FROM node:24-alpine AS frontend-build
WORKDIR /frontend

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build


# 2. Spring Boot 빌드
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /build

COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src

# Vue 빌드 결과물을 Spring Boot 정적 리소스에 포함
COPY --from=frontend-build /frontend/dist/ \
    /build/backend/src/main/resources/static/

RUN mvn -f backend/pom.xml -B -DskipTests package


# 3. 실행 이미지
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=backend-build /build/backend/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]