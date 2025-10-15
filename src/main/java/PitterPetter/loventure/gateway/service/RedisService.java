package PitterPetter.loventure.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
}
