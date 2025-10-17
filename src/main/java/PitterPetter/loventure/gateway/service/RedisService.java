package PitterPetter.loventure.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class RedisService {
    
    private static final Logger log = LoggerFactory.getLogger(RedisService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    
    // ========== 동기식 Redis 작업 예시 ==========
    
    /**
     * 키-값 저장 (동기식)
     */
    public void setValue(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
        log.info("Redis 저장 완료 - Key: {}, Value: {}", key, value);
    }
    
    /**
     * 키-값 저장 with TTL (동기식)
     */
    public void setValueWithTTL(String key, Object value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        log.info("Redis 저장 완료 (TTL: {}초) - Key: {}, Value: {}", ttlSeconds, key, value);
    }
    
    /**
     * 값 조회 (동기식)
     */
    public Object getValue(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        log.info("Redis 조회 - Key: {}, Value: {}", key, value);
        return value;
    }
    
    /**
     * 키 삭제 (동기식)
     */
    public boolean deleteKey(String key) {
        Boolean deleted = redisTemplate.delete(key);
        log.info("Redis 삭제 - Key: {}, 결과: {}", key, deleted);
        return deleted != null && deleted;
    }
    
    // ========== 비동기식 Redis 작업 예시 (WebFlux용) ==========
    
    /**
     * 키-값 저장 (비동기식)
     */
    public Mono<Boolean> setValueReactive(String key, Object value) {
        return reactiveRedisTemplate.opsForValue()
                .set(key, value)
                .doOnSuccess(result -> log.info("Redis 저장 완료 (Reactive) - Key: {}, Value: {}", key, value))
                .doOnError(error -> log.error("Redis 저장 실패 (Reactive) - Key: {}, Error: {}", key, error.getMessage()));
    }
    
    /**
     * 키-값 저장 with TTL (비동기식)
     */
    public Mono<Boolean> setValueWithTTLReactive(String key, Object value, Duration ttl) {
        return reactiveRedisTemplate.opsForValue()
                .set(key, value, ttl)
                .doOnSuccess(result -> log.info("Redis 저장 완료 (Reactive, TTL: {}) - Key: {}, Value: {}", ttl, key, value))
                .doOnError(error -> log.error("Redis 저장 실패 (Reactive) - Key: {}, Error: {}", key, error.getMessage()));
    }
    
    /**
     * 값 조회 (비동기식)
     */
    public Mono<Object> getValueReactive(String key) {
        return reactiveRedisTemplate.opsForValue()
                .get(key)
                .doOnNext(value -> log.info("Redis 조회 (Reactive) - Key: {}, Value: {}", key, value))
                .doOnError(error -> log.error("Redis 조회 실패 (Reactive) - Key: {}, Error: {}", key, error.getMessage()));
    }
    
    /**
     * 키 삭제 (비동기식)
     */
    public Mono<Boolean> deleteKeyReactive(String key) {
        return reactiveRedisTemplate.delete(key)
                .map(result -> result > 0)
                .doOnSuccess(deleted -> log.info("Redis 삭제 (Reactive) - Key: {}, 결과: {}", key, deleted))
                .doOnError(error -> log.error("Redis 삭제 실패 (Reactive) - Key: {}, Error: {}", key, error.getMessage()));
    }
    
    // ========== 실제 사용 예시 ==========
    
    /**
     * 티켓 정보 캐싱 (동기식)
     */
    public void cacheTicketInfo(String coupleId, Object ticketData, long ttlMinutes) {
        String key = "ticket:couple:" + coupleId;
        setValueWithTTL(key, ticketData, ttlMinutes * 60);
    }
    
    /**
     * 티켓 정보 캐싱 (비동기식)
     */
    public Mono<Boolean> cacheTicketInfoReactive(String coupleId, Object ticketData, Duration ttl) {
        String key = "ticket:couple:" + coupleId;
        return setValueWithTTLReactive(key, ticketData, ttl);
    }
    
    /**
     * 캐시된 티켓 정보 조회 (동기식)
     */
    public Object getCachedTicketInfo(String coupleId) {
        String key = "ticket:couple:" + coupleId;
        return getValue(key);
    }
    
    /**
     * 캐시된 티켓 정보 조회 (비동기식)
     */
    public Mono<Object> getCachedTicketInfoReactive(String coupleId) {
        String key = "ticket:couple:" + coupleId;
        return getValueReactive(key);
    }
    
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
     * coupleId로 티켓 정보 조회 (비동기식) - Regions Unlock 필터용
     * Key 형식: coupleId:{coupleId}
     */
    public Mono<Object> getCoupleTicketInfoReactive(String coupleId) {
        String key = "coupleId:" + coupleId;
        return getValueReactive(key)
            .doOnNext(value -> log.info("🎫 Regions Unlock - Redis 조회 (Reactive) - Key: {}, Value: {}", key, value));
    }
    
    /**
     * coupleId로 티켓 정보 업데이트 (동기식) - Regions Unlock 필터용
     * Key 형식: coupleId:{coupleId}
     * Write-Through 패턴 적용: Redis 저장 후 Stream 이벤트 발행
     */
    public void updateCoupleTicketInfo(String coupleId, Object ticketData) {
        String key = "coupleId:" + coupleId;
        setValue(key, ticketData);
        log.info("🎫 Regions Unlock - Redis 업데이트 - Key: {}, Value: {}", key, ticketData);
        
        // Write-Through 패턴: Redis Stream에 동기화 이벤트 발행
        publishSyncEventSync(coupleId, ticketData);
    }
    
    /**
     * coupleId로 티켓 정보 업데이트 (비동기식) - Regions Unlock 필터용
     * Key 형식: coupleId:{coupleId}
     */
    public Mono<Boolean> updateCoupleTicketInfoReactive(String coupleId, Object ticketData) {
        String key = "coupleId:" + coupleId;
        return setValueReactive(key, ticketData)
            .doOnSuccess(result -> log.info("🎫 Regions Unlock - Redis 업데이트 (Reactive) - Key: {}, Value: {}", key, ticketData));
    }
    
    // ========== Redis Write-Through 패턴 구현 ==========
    
    /**
     * Write-Through 패턴으로 티켓 정보 업데이트
     * Redis에 데이터를 저장하고 동시에 Redis Stream에 동기화 이벤트 발행
     */
    public Mono<Boolean> updateCoupleTicketInfoWriteThrough(String coupleId, Object ticketData) {
        String key = "coupleId:" + coupleId;
        
        log.info("🔄 Write-Through 패턴 시작 - coupleId: {}", coupleId);
        
        // 1. Redis에 데이터 저장
        return reactiveRedisTemplate.opsForValue()
            .set(key, ticketData)
            .flatMap(result -> {
                if (result) {
                    log.info("✅ Redis 저장 성공 - Key: {}, Value: {}", key, ticketData);
                    // 2. Redis Stream에 동기화 이벤트 발행
                    return publishSyncEvent(coupleId, ticketData);
                } else {
                    log.error("❌ Redis 저장 실패 - Key: {}", key);
                    return Mono.just(false);
                }
            })
            .doOnSuccess(success -> {
                if (success) {
                    log.info("🎉 Write-Through 패턴 완료 - coupleId: {}", coupleId);
                } else {
                    log.error("❌ Write-Through 패턴 실패 - coupleId: {}", coupleId);
                }
            })
            .doOnError(error -> log.error("🚨 Write-Through 패턴 에러 - coupleId: {}, error: {}", coupleId, error.getMessage()));
    }
    
    /**
     * Redis Stream에 동기화 이벤트 발행
     * Auth Service에서 구독하여 DB 동기화를 수행
     */
    private Mono<Boolean> publishSyncEvent(String coupleId, Object ticketData) {
        try {
            Map<String, Object> event = Map.of(
                "coupleId", coupleId,
                "ticketData", ticketData,
                "timestamp", System.currentTimeMillis(),
                "source", "gateway",
                "eventType", "ticket-update"
            );
            
            log.info("📡 Redis Stream 이벤트 발행 - coupleId: {}, eventType: ticket-update", coupleId);
            
            return reactiveRedisTemplate.opsForStream()
                .add("ticket-sync-stream", event)
                .map(RecordId::getValue)
                .map(Objects::nonNull)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("✅ Redis Stream 이벤트 발행 성공 - coupleId: {}", coupleId);
                    } else {
                        log.error("❌ Redis Stream 이벤트 발행 실패 - coupleId: {}", coupleId);
                    }
                })
                .doOnError(error -> log.error("🚨 Redis Stream 이벤트 발행 에러 - coupleId: {}, error: {}", coupleId, error.getMessage()));
                
        } catch (Exception e) {
            log.error("🚨 Redis Stream 이벤트 생성 에러 - coupleId: {}, error: {}", coupleId, e.getMessage());
            return Mono.just(false);
        }
    }
    
    /**
     * Redis Stream에 동기화 이벤트 발행 (동기식)
     * 동기식 메서드에서도 사용할 수 있도록 제공
     */
    public void publishSyncEventSync(String coupleId, Object ticketData) {
        try {
            Map<String, Object> event = Map.of(
                "coupleId", coupleId,
                "ticketData", ticketData,
                "timestamp", System.currentTimeMillis(),
                "source", "gateway",
                "eventType", "ticket-update"
            );
            
            log.info("📡 Redis Stream 이벤트 발행 (동기식) - coupleId: {}, eventType: ticket-update", coupleId);
            
            // 동기식으로 Redis Stream에 이벤트 추가
            RecordId recordId = redisTemplate.opsForStream().add("ticket-sync-stream", event);
            
            if (recordId != null) {
                log.info("✅ Redis Stream 이벤트 발행 성공 (동기식) - coupleId: {}, recordId: {}", coupleId, recordId.getValue());
            } else {
                log.error("❌ Redis Stream 이벤트 발행 실패 (동기식) - coupleId: {}", coupleId);
            }
            
        } catch (Exception e) {
            log.error("🚨 Redis Stream 이벤트 발행 에러 (동기식) - coupleId: {}, error: {}", coupleId, e.getMessage());
        }
    }
    
    /**
     * 사용자 세션 저장 (동기식)
     */
    public void storeUserSession(String sessionId, Object sessionData, long ttlHours) {
        String key = "session:" + sessionId;
        setValueWithTTL(key, sessionData, ttlHours * 3600);
    }
    
    /**
     * 사용자 세션 저장 (비동기식)
     */
    public Mono<Boolean> storeUserSessionReactive(String sessionId, Object sessionData, Duration ttl) {
        String key = "session:" + sessionId;
        return setValueWithTTLReactive(key, sessionData, ttl);
    }
    
    // ========== Redis 초기화 관련 메서드 ==========
    
    /**
     * Redis 전체 데이터 삭제 (동기식)
     */
    public void flushAll() {
        try {
            redisTemplate.getConnectionFactory()
                    .getConnection()
                    .flushAll();
            log.info("🗑️ Redis 전체 데이터 삭제 완료 (동기식)");
        } catch (Exception e) {
            log.error("❌ Redis 전체 데이터 삭제 실패 (동기식): {}", e.getMessage());
            throw new RuntimeException("Redis 전체 데이터 삭제 실패", e);
        }
    }
    
    /**
     * Redis 전체 데이터 삭제 (비동기식)
     */
    public Mono<Boolean> flushAllReactive() {
        return reactiveRedisTemplate.getConnectionFactory()
                .getReactiveConnection()
                .serverCommands()
                .flushAll()
                .doOnSuccess(result -> log.info("🗑️ Redis 전체 데이터 삭제 완료 (비동기식)"))
                .doOnError(error -> log.error("❌ Redis 전체 데이터 삭제 실패 (비동기식): {}", error.getMessage()))
                .then(Mono.just(true));
    }
    
    /**
     * Redis 데이터베이스 초기화 (현재 DB만)
     */
    public void flushDb() {
        try {
            redisTemplate.getConnectionFactory()
                    .getConnection()
                    .flushDb();
            log.info("🗑️ Redis 현재 데이터베이스 초기화 완료");
        } catch (Exception e) {
            log.error("❌ Redis 현재 데이터베이스 초기화 실패: {}", e.getMessage());
            throw new RuntimeException("Redis 데이터베이스 초기화 실패", e);
        }
    }
    
    /**
     * Redis 데이터베이스 초기화 (비동기식)
     */
    public Mono<Boolean> flushDbReactive() {
        return reactiveRedisTemplate.getConnectionFactory()
                .getReactiveConnection()
                .serverCommands()
                .flushDb()
                .doOnSuccess(result -> log.info("🗑️ Redis 현재 데이터베이스 초기화 완료 (비동기식)"))
                .doOnError(error -> log.error("❌ Redis 현재 데이터베이스 초기화 실패 (비동기식): {}", error.getMessage()))
                .then(Mono.just(true));
    }
}
