# syntax=docker/dockerfile:1
# 빌드: docker buildx build --platform linux/amd64,linux/arm64 -t <ecr>/indig-concert:$(git rev-parse --short HEAD) --push .
FROM --platform=$BUILDPLATFORM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle gradle.properties* ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# 🔴 amd64 · arm64 둘 다 올립니다 (AWS 노드는 t4g · 온프레미스는 Intel PC) — 노드와 다른 아키텍처만 있으면 exec format error
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8083 8093
# MaxMetaspaceSize 160m — JPA 가 Gateway 보다 클래스를 많이 씁니다
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=70", \
  "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=160m", \
  "-Duser.timezone=Asia/Seoul", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "app.jar"]
