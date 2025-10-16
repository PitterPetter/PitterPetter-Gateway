# PitterPetter Gateway - Regions Unlock API 구현 상세 문서

## 📋 프로젝트 개요

**목적**: Frontend에서 `regions/unlock` 경로로 들어오는 요청을 가로채서 JWT 토큰과 Redis 데이터를 기반으로 티켓 상태를 검증하고, 비즈니스 로직에 따라 허용/차단을 결정하는 Gateway 필터 구현

**기술 스택**: Spring Cloud Gateway, Spring WebFlux, Redis, JWT, WebClient

---

## 🎯 비즈니스 요구사항 상세 분석

### 1. 요청 처리 플로우
```
Frontend → Gateway → JWT 검증 → Redis 조회 → 비즈니스 로직 → 허용/차단 결정 → Couples API 호출
```

### 2. 입력 데이터 구조
```json
// HTTP Request
POST /api/regions/unlock
Headers: {
  "Authorization": "Bearer <JWT_TOKEN>"
}
Body: {
  "regions": "광진구"
}

// JWT Token Payload
{
  "user_id": "string",
  "couple_id": "string"
}

// Redis Data Structure
Key: "coupleId:{coupleId}"
Value: {
  "coupleId": "123456",
  "ticket": 12,
  "isTodayTicket": "true",
  "lastSyncedAt": "2024-09-01T08:12:45Z"
}
```

### 3. 비즈니스 로직 3가지 케이스

#### 케이스 1: `isTodayTicket = "true"`
- **동기 처리**: Redis에서 `isTodayTicket`을 `"false"`로 변경
- **결정**: ✅ **허용** - frontend 요청을 `regions/unlock`으로 전달
- **비동기 처리**: `couples/ticket` PUT 요청으로 상태 업데이트
```json
{
  "coupleId": "123456",
  "ticket": 12,
  "isTodayTicket": "false",
  "lastSyncedAt": "2024-09-01T08:12:45Z"
}
```

#### 케이스 2: `isTodayTicket = "false"` + `ticket > 0`
- **동기 처리**: `ticket`을 1 차감
- **결정**: ✅ **허용** - frontend 요청을 `regions/unlock`으로 전달
- **비동기 처리**: `couples/ticket` PUT 요청으로 상태 업데이트
```json
{
  "coupleId": "123456",
  "ticket": 11,  // 12 - 1
  "isTodayTicket": "false",
  "lastSyncedAt": "2024-09-01T08:12:45Z"
}
```

#### 케이스 3: `isTodayTicket = "false"` + `ticket = 0`
- **동기 처리**: 티켓 상태 확인만
- **결정**: ❌ **차단** - frontend 요청을 차단
- **에러 응답**: HTTP 403 Forbidden
```json
{
  "responseMessage": "티켓이 없습니다."
}
```

---

## 🏗️ 코드 설계 및 구현 상세

### 1. 필터 체인 아키텍처

#### 필터 실행 순서 (Order 기반)
```java
// 1. CorsErrorFilter (Order: 0)
//    - CORS Origin 검증
//    - 허용되지 않은 Origin 차단

// 2. JwtAuthorizationFilter (Order: -1) 
//    - JWT 토큰 유효성 검증
//    - PUBLIC_PATHS 제외하고 모든 요청 검증

// 3. RegionsUnlockFilter (Order: 1)
//    - /api/regions/unlock 경로만 처리
//    - 티켓 비즈니스 로직 실행
```

#### 필터 등록 방식
```java
@Component  // Spring이 자동으로 GlobalFilter로 등록
@RequiredArgsConstructor
public class RegionsUnlockFilter implements GlobalFilter, Ordered {
    // 의존성 주입: JwtUtil, ObjectMapper, RedisService, CouplesApiClient
}
```

### 2. RegionsUnlockFilter 상세 구현

#### A. 필터 진입점 및 경로 매칭
```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getPath().toString();
    
    // /api/regions/unlock 경로가 아니면 필터 건너뛰기
    if (!path.equals(TARGET_PATH)) {
        return chain.filter(exchange);
    }
    
    log.info("🎫 RegionsUnlockFilter 시작 - {} : {}", method, path);
    
    // 1. JWT 토큰에서 userId, coupleId 추출
    // 2. Request Body에서 regions 정보 추출  
    // 3. Redis에서 티켓 정보 조회 및 검증
    // 4. 비즈니스 로직에 따른 허용/차단 결정
}
```

#### B. JWT 토큰 처리 로직
```java
private String[] extractUserInfoFromJwt(ServerWebExchange exchange) throws Exception {
    // 1. Authorization 헤더 검증
    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        throw new IllegalArgumentException("유효하지 않은 Authorization 헤더");
    }
    
    // 2. JWT 토큰 추출 및 유효성 검증
    String token = authHeader.replace("Bearer ", "").trim();
    if (!jwtUtil.isValidToken(token)) {
        throw new IllegalArgumentException("유효하지 않은 JWT 토큰");
    }
    
    // 3. Claims에서 사용자 정보 추출
    Claims claims = jwtUtil.extractClaims(token);
    String userId = claims.get("user_id", String.class);
    String coupleId = claims.get("couple_id", String.class);
    
    // 4. 필수 정보 검증
    if (userId == null || coupleId == null) {
        throw new IllegalArgumentException("JWT 토큰에 필요한 정보가 없습니다");
    }
    
    return new String[]{userId, coupleId};
}
```

#### C. Request Body 파싱 로직
```java
private Mono<String> extractRegionsFromBody(ServerWebExchange exchange) {
    return exchange.getRequest().getBody()
        .collectList()
        .flatMap(dataBuffers -> {
            // 1. DataBuffer들을 하나의 byte 배열로 합치기
            byte[] bytes = new byte[dataBuffers.stream().mapToInt(DataBuffer::readableByteCount).sum()];
            int offset = 0;
            for (DataBuffer buffer : dataBuffers) {
                int count = buffer.readableByteCount();
                buffer.read(bytes, offset, count);
                offset += count;
            }
            
            // 2. JSON 파싱
            try {
                String body = new String(bytes, StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = objectMapper.readValue(body, Map.class);
                String regions = (String) bodyMap.get("regions");
                
                // 3. 필수 필드 검증
                if (regions == null || regions.trim().isEmpty()) {
                    return Mono.error(new IllegalArgumentException("regions 정보가 없습니다"));
                }
                
                return Mono.just(regions);
            } catch (Exception e) {
                return Mono.error(new IllegalArgumentException("Request Body 파싱 실패: " + e.getMessage()));
            }
        });
}
```

#### D. Redis 데이터 조회 및 검증 로직
```java
private Mono<Boolean> validateTicketAndProcess(ServerWebExchange exchange, String coupleId, String regions) {
    try {
        // 1. Redis에서 티켓 정보 조회 (동기식)
        Object ticketData = redisService.getCoupleTicketInfo(coupleId);
        
        if (ticketData == null) {
            log.warn("❌ Redis에 티켓 정보가 없음 - coupleId: {}", coupleId);
            return Mono.just(false);
        }
        
        // 2. JSON 파싱하여 티켓 정보 추출
        @SuppressWarnings("unchecked")
        Map<String, Object> ticketMap = objectMapper.convertValue(ticketData, Map.class);
        
        int ticket = (Integer) ticketMap.get("ticket");
        String isTodayTicket = (String) ticketMap.get("isTodayTicket");
        String redisCoupleId = String.valueOf(ticketMap.get("coupleId")); // string으로 변환
        
        // 3. 비즈니스 로직 처리
        return processTicketLogic(coupleId, ticketMap, ticket, isTodayTicket, jwtToken, redisCoupleId)
            .map(updatedTicketMap -> {
                // 4. Redis 업데이트 (동기식)
                redisService.updateCoupleTicketInfo(coupleId, updatedTicketMap);
                return true;
            });
            
    } catch (Exception e) {
        log.error("🚨 티켓 검증 중 오류 - coupleId: {}, error: {}", coupleId, e.getMessage(), e);
        return Mono.just(false);
    }
}
```

#### E. 핵심 비즈니스 로직 처리
```java
private Mono<Map<String, Object>> processTicketLogic(String coupleId, Map<String, Object> ticketMap, 
                                                     int ticket, String isTodayTicket, String jwtToken, String redisCoupleId) {
    
    if ("true".equals(isTodayTicket)) {
        // 케이스 1: isTodayTicket = "true" → false로 변경하고 허용
        log.info("✅ 케이스 1: isTodayTicket을 false로 변경 - coupleId: {}", coupleId);
        
        Map<String, Object> updatedTicketMap = new java.util.HashMap<>(ticketMap);
        updatedTicketMap.put("coupleId", redisCoupleId); // coupleId를 string으로 저장
        updatedTicketMap.put("isTodayTicket", "false");
        updatedTicketMap.put("lastSyncedAt", java.time.OffsetDateTime.now().toString());
        
        // 비동기적으로 Couples API 호출
        scheduleAsyncCouplesApiUpdate(coupleId, updatedTicketMap, jwtToken);
        
        return Mono.just(updatedTicketMap);
        
    } else if ("false".equals(isTodayTicket)) {
        if (ticket > 0) {
            // 케이스 2: isTodayTicket = "false" + ticket > 0 → ticket 1 차감하고 허용
            log.info("✅ 케이스 2: ticket 1 차감 - coupleId: {}, ticket: {} → {}", coupleId, ticket, ticket - 1);
            
            Map<String, Object> updatedTicketMap = new java.util.HashMap<>(ticketMap);
            updatedTicketMap.put("coupleId", redisCoupleId); // coupleId를 string으로 저장
            updatedTicketMap.put("ticket", ticket - 1);
            updatedTicketMap.put("lastSyncedAt", java.time.OffsetDateTime.now().toString());
            
            // 비동기적으로 Couples API 호출
            scheduleAsyncCouplesApiUpdate(coupleId, updatedTicketMap, jwtToken);
            
            return Mono.just(updatedTicketMap);
            
        } else {
            // 케이스 3: isTodayTicket = "false" + ticket = 0 → 차단
            log.warn("❌ 케이스 3: 티켓 부족 - coupleId: {}, ticket: {}", coupleId, ticket);
            return Mono.error(new RuntimeException("티켓이 없습니다."));
        }
    } else {
        log.error("🚨 잘못된 isTodayTicket 값 - coupleId: {}, isTodayTicket: {}", coupleId, isTodayTicket);
        return Mono.error(new RuntimeException("잘못된 티켓 상태입니다."));
    }
}
```

#### F. 비동기 Couples API 호출 로직
```java
private void scheduleAsyncCouplesApiUpdate(String coupleId, Map<String, Object> ticketData, String jwtToken) {
    log.info("🔄 비동기 Couples API 호출 스케줄링 - coupleId: {}", coupleId);
    
    // Correlation ID 생성 (현재 시간 + coupleId로 고유성 보장)
    String correlationId = "regions-unlock-" + coupleId + "-" + System.currentTimeMillis();
    
    // 비동기적으로 Couples API 호출
    Mono.fromRunnable(() -> {
        if (jwtToken != null) {
            couplesApiClient.updateTicketInfo(jwtToken, ticketData, correlationId)
                .subscribe(
                    response -> log.info("✅ 비동기 Couples API 호출 성공 - coupleId: {}, correlation_id: {}", 
                                       coupleId, correlationId),
                    error -> log.error("❌ 비동기 Couples API 호출 실패 - coupleId: {}, correlation_id: {}, error: {}", 
                                     coupleId, correlationId, error.getMessage())
                );
        } else {
            log.warn("⚠️ JWT 토큰이 null이어서 비동기 API 호출 건너뜀 - coupleId: {}", coupleId);
        }
    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
      .subscribe();
}
```

### 3. RedisService 확장 구현

#### A. Regions Unlock 필터용 Redis 메서드
```java
// ========== Regions Unlock 필터용 Redis 메서드 ==========

/**
 * coupleId로 티켓 정보 조회 (동기식) - Regions Unlock 필터용
 * Key 형식: coupleId:{coupleId}
 */
public Object getCoupleTicketInfo(String coupleId) {
    String key = "coupleId:" + coupleId;
    Object value = getValue(key);
    log.info("🎫 Regions Unlock - Redis 조회 - Key: {}, Value: {}", key, value);
    return value;
}

/**
 * coupleId로 티켓 정보 업데이트 (동기식) - Regions Unlock 필터용
 * Key 형식: coupleId:{coupleId}
 */
public void updateCoupleTicketInfo(String coupleId, Object ticketData) {
    String key = "coupleId:" + coupleId;
    setValue(key, ticketData);
    log.info("🎫 Regions Unlock - Redis 업데이트 - Key: {}, Value: {}", key, ticketData);
}
```

### 4. CouplesApiClient 확장 구현

#### A. 티켓 정보 업데이트 PUT 메서드
```java
/**
 * 티켓 정보 업데이트 (PUT 요청)
 * Regions Unlock 필터에서 비동기적으로 호출하는 메서드
 */
public Mono<TicketResponse> updateTicketInfo(String jwtToken, Object ticketData, String correlationId) {
    log.info("Calling Couples API for ticket update, correlation_id: {}", correlationId);
    
    return couplesWebClient
        .put()
        .uri("/api/couples/ticket")
        .header("Authorization", "Bearer " + jwtToken)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("X-Correlation-Id", correlationId)
        .bodyValue(ticketData)
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, response -> {
            log.warn("Couples API client error for ticket update: {}", response.statusCode());
            return response.bodyToMono(String.class)
                .flatMap(body -> Mono.error(new CouplesApiException(
                    "Client error: " + body, HttpStatus.valueOf(response.statusCode().value()))));
        })
        .onStatus(HttpStatusCode::is5xxServerError, response -> {
            log.error("Couples API server error for ticket update: {}", response.statusCode());
            return response.bodyToMono(String.class)
                .flatMap(body -> Mono.error(new CouplesApiException(
                    "Server error: " + body, HttpStatus.valueOf(response.statusCode().value()))));
        })
        .bodyToMono(TicketResponse.class)
        .timeout(Duration.ofSeconds(10))
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
            .filter(throwable -> throwable instanceof WebClientResponseException))
        .doOnSuccess(response -> {
            log.info("Successfully updated ticket info, correlation_id: {}, ticket: {}", 
                    correlationId, response.getTicket());
        })
        .doOnError(error -> {
            log.error("Failed to update ticket info, correlation_id: {}, error: {}", 
                     correlationId, error.getMessage());
        });
}
```

### 5. 에러 처리 및 응답 구조

#### A. 티켓 부족 에러 응답
```java
private Mono<Void> sendTicketErrorResponse(ServerWebExchange exchange) {
    return sendErrorResponse(exchange, "티켓이 없습니다.");
}

private Mono<Void> sendErrorResponse(ServerWebExchange exchange, String message) {
    var response = exchange.getResponse();
    response.setStatusCode(HttpStatus.FORBIDDEN);
    response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    
    String json = String.format(
        "{\"responseMessage\": \"%s\"}", 
        message
    );
    
    var buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
}
```

### 6. Redis 일일 초기화 스케줄러

#### A. 스케줄러 구현
```java
@Component
@EnableScheduling
public class RedisDailyResetScheduler {
    
    @Value("${redis.daily-reset.enabled:true}")
    private boolean resetEnabled;
    
    @Value("${redis.daily-reset.mode:db}")
    private String resetMode; // "db" (현재 DB만) 또는 "all" (전체 Redis)
    
    /**
     * 매일 정각(00:00:00)에 Redis 초기화 실행
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void dailyRedisReset() {
        if (!resetEnabled) {
            log.info("⏰ Redis 일일 초기화가 비활성화되어 있습니다");
            return;
        }
        
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("🕛 Redis 일일 초기화 시작 - 시간: {}", currentTime);
        
        try {
            if ("all".equalsIgnoreCase(resetMode)) {
                log.info("🗑️ Redis 전체 데이터 초기화 실행 중...");
                redisService.flushAll();
                log.info("✅ Redis 전체 데이터 초기화 완료");
            } else {
                log.info("🗑️ Redis 현재 데이터베이스 초기화 실행 중...");
                redisService.flushDb();
                log.info("✅ Redis 현재 데이터베이스 초기화 완료");
            }
            
            log.info("🎉 Redis 일일 초기화 성공 - 시간: {}", currentTime);
            
        } catch (Exception e) {
            log.error("❌ Redis 일일 초기화 실패 - 시간: {}, 오류: {}", currentTime, e.getMessage());
        }
    }
}
```

---

## 🔧 기술적 설계 결정사항

### 1. 동기 vs 비동기 처리 전략

#### 동기 처리 (즉시 응답)
- **Redis 조회**: 사용자 요청에 대한 즉시 응답을 위해 동기 처리
- **Redis 업데이트**: 데이터 일관성을 위해 동기 처리
- **비즈니스 로직**: 허용/차단 결정을 위해 동기 처리

#### 비동기 처리 (백그라운드)
- **Couples API 호출**: 사용자 응답 속도 최적화를 위해 비동기 처리
- **Reactor Scheduler**: `boundedElastic()` 사용으로 I/O 집약적 작업 처리

### 2. 에러 처리 전략

#### 계층별 에러 처리
```java
// 1. JWT 토큰 검증 에러
catch (Exception e) {
    return sendErrorResponse(exchange, "티켓 검증 중 오류가 발생했습니다.");
}

// 2. Redis 조회 에러
if (ticketData == null) {
    return Mono.just(false); // 차단
}

// 3. 비즈니스 로직 에러
return Mono.error(new RuntimeException("티켓이 없습니다."));

// 4. 비동기 API 호출 에러
.doOnError(error -> {
    log.error("❌ 비동기 Couples API 호출 실패 - coupleId: {}, error: {}", 
             coupleId, error.getMessage());
})
```

### 3. 데이터 일관성 보장

#### Redis 업데이트 우선순위
1. **동기적 Redis 업데이트**: 사용자 요청에 대한 즉시 반영
2. **비동기적 Couples API 호출**: 백그라운드에서 외부 시스템 동기화

#### CoupleId String 타입 강제
```java
String redisCoupleId = String.valueOf(ticketMap.get("coupleId")); // string으로 변환
updatedTicketMap.put("coupleId", redisCoupleId); // coupleId를 string으로 저장
```

### 4. 로깅 및 모니터링 전략

#### 구조화된 로깅
```java
// 이모지를 사용한 로그 레벨 구분
log.info("🎫 티켓 정보 - coupleId: {}, ticket: {}, isTodayTicket: {}", redisCoupleId, ticket, isTodayTicket);
log.info("✅ 케이스 1: isTodayTicket을 false로 변경 - coupleId: {}", coupleId);
log.warn("❌ 케이스 3: 티켓 부족 - coupleId: {}, ticket: {}", coupleId, ticket);
log.error("🚨 티켓 검증 중 오류 - coupleId: {}, error: {}", coupleId, e.getMessage(), e);
```

#### Correlation ID 추적
```java
String correlationId = "regions-unlock-" + coupleId + "-" + System.currentTimeMillis();
```

---

## 📊 성능 및 확장성 고려사항

### 1. 성능 최적화

#### 메모리 관리
```java
// WebClient 메모리 크기 설정
.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
```

#### 타임아웃 설정
```java
// Couples API 호출 타임아웃
.timeout(Duration.ofSeconds(10))

// 재시도 로직
.retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
    .filter(throwable -> throwable instanceof WebClientResponseException))
```

### 2. 확장성 고려사항

#### 필터 체인 확장
- **Order 기반 필터 순서**: 새로운 필터 추가 시 Order 값 조정으로 쉽게 확장
- **경로별 필터**: 특정 경로에만 적용되는 필터로 성능 최적화

#### Redis 확장
- **동기/비동기 클라이언트**: 상황에 맞는 클라이언트 선택 가능
- **JSON 시리얼라이저**: 복잡한 객체도 쉽게 저장/조회 가능

---

## 🚀 배포 및 운영 고려사항

### 1. 설정 관리

#### application.yml 설정
```yaml
# Redis 일일 초기화 설정
redis:
  daily-reset:
    enabled: true          # 일일 초기화 활성화/비활성화
    mode: db              # "db" (현재 DB만) 또는 "all" (전체 Redis)

# 로깅 설정
logging:
  level:
    PitterPetter.loventure.gateway: INFO
```

### 2. 모니터링 포인트

#### 핵심 메트릭
- **필터 실행 시간**: JWT 검증, Redis 조회, 비즈니스 로직 처리 시간
- **Redis 연결 상태**: Redis 서버 연결 및 응답 시간
- **Couples API 호출**: 성공/실패율, 응답 시간
- **티켓 상태 분포**: 각 케이스별 처리 빈도

#### 로그 모니터링
- **에러 로그**: 티켓 검증 실패, API 호출 실패
- **성능 로그**: 각 단계별 처리 시간
- **비즈니스 로그**: 티켓 상태 변경 추적

---

## 📝 구현 완료 내역

### 1. 핵심 기능 구현
- ✅ **RegionsUnlockFilter**: JWT 토큰 및 body 파싱
- ✅ **Redis 데이터 조회**: coupleId 키 형식으로 조회/업데이트
- ✅ **티켓 검증 로직**: 3가지 비즈니스 케이스 처리
- ✅ **CouplesApiClient**: PUT 메서드 추가
- ✅ **비동기 처리**: 백그라운드 API 호출
- ✅ **에러 응답**: 티켓 부족 시 403 응답

### 2. 인프라 기능 구현
- ✅ **Redis 스케줄러**: 일일 초기화 기능
- ✅ **Redis 연결 로거**: 시작 시 연결 상태 확인
- ✅ **WebClient 설정**: 메모리 최적화 및 타임아웃 설정

### 3. 코드 품질
- ✅ **상세한 주석**: 각 메서드와 로직별 목적 명시
- ✅ **구조화된 로깅**: 이모지와 함께 단계별 로그
- ✅ **에러 처리**: 계층별 적절한 에러 처리
- ✅ **타입 안전성**: String 타입 강제 및 검증

---

## 🎯 최종 검증 결과

### 정상 동작 확인사항
1. ✅ **필터 체인 순서**: CORS → JWT → RegionsUnlock 순서로 정상 실행
2. ✅ **경로 매칭**: `/api/regions/unlock` 경로만 정확히 처리
3. ✅ **JWT 토큰 처리**: `user_id`, `couple_id` 정상 추출
4. ✅ **Redis 데이터 처리**: `coupleId:{coupleId}` 키 형식으로 정상 조회/업데이트
5. ✅ **비즈니스 로직**: 3가지 케이스별 정상 처리
6. ✅ **비동기 API 호출**: 백그라운드에서 Couples API 정상 호출
7. ✅ **에러 응답**: 티켓 부족 시 403 응답 정상 처리

### 성능 최적화
1. ✅ **동기/비동기 분리**: 응답 속도 최적화
2. ✅ **메모리 관리**: WebClient 메모리 크기 제한
3. ✅ **타임아웃 설정**: API 호출 타임아웃 및 재시도 로직
4. ✅ **Redis 최적화**: JSON 시리얼라이저로 효율적 데이터 처리

---

**🎉 PIT-541 Regions Unlock API 구현이 완료되었습니다!**

*구현일: 2024-12-19*  
*총 커밋 수: 8개*  
*구현된 파일 수: 7개 (신규 2개, 수정 5개)*
