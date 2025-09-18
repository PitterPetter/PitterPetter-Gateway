# 💌 Loventure - API Gateway Service 

Loveventure의 **MSA 아키텍처**에서 진입점 역할을 하는 API Gateway 서비스.  
Spring Cloud Gateway를 기반으로 각 도메인 서비스(Auth, Course, Content, Review 등)로 요청을 라우팅하고,  
공통 인증/인가, 로깅, 모니터링, CORS 제어를 담당함.

---

## 🔧 Tech Stack

- **Lang/Runtime**: Java 17, Spring Boot 3.5.5
- **Framework**: Spring Cloud Gateway 2023.x
- **Auth**: JWT (Auth Service 연동)
- **Config**: Spring Cloud Config, Eureka (선택적)
- **Observability**: Micrometer, Actuator, Logback

---

## 📂 프로젝트 구조

```
gateway-service/
├─ src/main/java/com/loventure/gateway
│  ├─ config/        # 라우팅, CORS, 필터 설정
│  ├─ filter/        # JWT 인증/인가 전역 필터
│  └─ exception/     # 예외 처리
└─ src/test/java/... # 게이트웨이 라우팅/보안 테스트
```

---

## ✨ 주요 기능

- **라우팅**: 서비스별 엔드포인트로 요청 전달
  - `/api/auth/**` → Auth Service
  - `/api/course/**` → Course Service
  - `/api/content/**` → Content Service
  - `/api/review/**` → Review Service

- **인증/인가**
  - JWT 토큰 유효성 검증 (Auth Service 발급 토큰)
  - 특정 경로(로그인/회원가입)는 화이트리스트 처리

- **CORS**
  - 프론트엔드(Web) 클라이언트 도메인 허용

- **관측성**
  - 요청/응답 로깅
  - Actuator `/actuator/health`, `/actuator/gateway/routes`

---

## ⚙️ application.yml 예시

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway-service

  cloud:
    gateway:
      default-filters:
        - name: JwtAuthenticationFilter
      routes:
        - id: auth-service
          uri: http://auth-service:8081
          predicates:
            - Path=/api/auth/**
        - id: course-service
          uri: http://course-service:8082
          predicates:
            - Path=/api/course/**
        - id: content-service
          uri: http://content-service:8083
          predicates:
            - Path=/api/content/**
        - id: review-service]
```

---

## 🐳 로컬 실행

```bash
./gradlew bootRun
```

---

## 🧩 브랜치 전략
* main: 배포용 안정 버전
* develop: 통합 개발 브랜치
* feature/PID-이슈번호: 기능 단위 개발 브랜치

---

## 📜 커밋 규칙
* feat: 새로운 기능 추가
* fix: 버그 수정
* docs: 문서 수정
* refactor: 코드 리팩토링
* test: 테스트 코드
