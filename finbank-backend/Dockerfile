# 1. 빌드 단계 (Java 17 기준)
FROM amazoncorretto:17-alpine AS build
WORKDIR /app
COPY . .
# 실행 권한 부여 및 빌드
RUN chmod +x gradlew
RUN ./gradlew clean bootJar

# 2. 실행 단계
FROM amazoncorretto:17-alpine
WORKDIR /app
# 빌드된 jar 파일을 복사 (보통 build/libs 폴더에 생성됨)
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]