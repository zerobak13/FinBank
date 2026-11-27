# 1. 빌드 단계 (Java 17 기준)
FROM amazoncorretto:17-alpine AS build
WORKDIR /app
# .dockerignore가 build/·.gradle/과 로컬 비밀 설정(application-local.yml)을 빼고 복사한다
COPY . .
RUN chmod +x gradlew && ./gradlew clean bootJar

# 2. 실행 단계
FROM amazoncorretto:17-alpine
WORKDIR /app
# root가 아닌 계정으로 실행한다
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
# 업무 날짜(실행일·연체 기준일)는 한국 시간 — 클라우드 기본값(UTC)을 따르지 않게 고정한다
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
