# ---- Build stage: Gradle + JDK 21 (cache-friendly) ----
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Gradle wrapper & 빌드 스크립트만 먼저 복사 → 의존성 캐시
# Gradle wrapper를 명시적으로 복사 (핵심)
COPY gradlew ./
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/

COPY settings.gradle* build.gradle* ./
RUN chmod +x gradlew
# 플러그인/의존성만 미리 내려받아 캐시 (소스 없을 때 실패해도 캐시엔 도움)
RUN ./gradlew --no-daemon dependencies || true
#RUN ./gradlew --no-daemon build -x test || true

# 애플리케이션 소스 복사 후 빌드
COPY src ./src
# 테스트는 컨테이너 빌드에서 생략(원하면 제거)
RUN ./gradlew --no-daemon bootJar -x test

# ---- Runtime stage: JRE 21 slim ----
FROM eclipse-temurin:17-jre-jammy AS runtime

# 비루트 실행 권장
RUN useradd -ms /bin/bash spring
USER spring:spring

WORKDIR /app
# 빌드 산출물 복사 (필요시 파일명 고정 가능)
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

LABEL authors="wixdom"

ENV SERVER_PORT=8000
EXPOSE 8000

# 컨테이너 환경에서 메모리 친화 옵션 + 포트 주입
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Seoul"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=$SERVER_PORT -jar /app/app.jar"]