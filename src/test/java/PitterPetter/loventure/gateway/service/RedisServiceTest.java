package PitterPetter.loventure.gateway.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "spring.redis.database=1" // 테스트용 DB 사용
})
class RedisServiceTest {

    @Autowired
    private RedisService redisService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void testSyncRedisOperations() {
        // Given
        String key = "test:sync:key";
        String value = "test-value";
        
        // When & Then
        redisService.setValue(key, value);
        Object retrievedValue = redisService.getValue(key);
        
        assertEquals(value, retrievedValue);
        
        // Cleanup
        redisService.deleteKey(key);
    }

    @Test
    void testSyncRedisWithTTL() throws InterruptedException {
        // Given
        String key = "test:sync:ttl:key";
        String value = "test-value-ttl";
        long ttlSeconds = 2;
        
        // When
        redisService.setValueWithTTL(key, value, ttlSeconds);
        Object retrievedValue = redisService.getValue(key);
        
        // Then
        assertEquals(value, retrievedValue);
        
        // Wait for TTL expiration
        Thread.sleep((ttlSeconds + 1) * 1000);
        
        // Should be expired
        Object expiredValue = redisService.getValue(key);
        assertNull(expiredValue);
    }

    @Test
    void testReactiveRedisOperations() {
        // Given
        String key = "test:reactive:key";
        String value = "test-reactive-value";
        
        // When & Then
        StepVerifier.create(redisService.setValueReactive(key, value))
                .expectNext(true)
                .verifyComplete();
        
        StepVerifier.create(redisService.getValueReactive(key))
                .expectNext(value)
                .verifyComplete();
        
        StepVerifier.create(redisService.deleteKeyReactive(key))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void testReactiveRedisWithTTL() {
        // Given
        String key = "test:reactive:ttl:key";
        String value = "test-reactive-value-ttl";
        Duration ttl = Duration.ofSeconds(2);
        
        // When
        StepVerifier.create(redisService.setValueWithTTLReactive(key, value, ttl))
                .expectNext(true)
                .verifyComplete();
        
        StepVerifier.create(redisService.getValueReactive(key))
                .expectNext(value)
                .verifyComplete();
        
        // Wait for TTL expiration
        StepVerifier.create(redisService.getValueReactive(key)
                .delayElement(Duration.ofSeconds(3)))
                .expectNextCount(0)
                .verifyComplete();
    }

    @Test
    void testTicketCacheOperations() {
        // Given
        String coupleId = "test-couple-123";
        Object ticketData = Map.of(
            "coupleId", coupleId,
            "ticketCount", 5,
            "cachedAt", System.currentTimeMillis()
        );
        
        // When & Then (동기식)
        redisService.cacheTicketInfo(coupleId, ticketData, 5);
        Object cachedData = redisService.getCachedTicketInfo(coupleId);
        
        assertNotNull(cachedData);
        assertEquals(ticketData, cachedData);
        
        // Cleanup
        redisService.deleteKey("ticket:couple:" + coupleId);
    }

    @Test
    void testTicketCacheOperationsReactive() {
        // Given
        String coupleId = "test-couple-reactive-456";
        Object ticketData = Map.of(
            "coupleId", coupleId,
            "ticketCount", 3,
            "cachedAt", System.currentTimeMillis()
        );
        Duration ttl = Duration.ofMinutes(5);
        
        // When & Then (비동기식)
        StepVerifier.create(redisService.cacheTicketInfoReactive(coupleId, ticketData, ttl))
                .expectNext(true)
                .verifyComplete();
        
        StepVerifier.create(redisService.getCachedTicketInfoReactive(coupleId))
                .expectNext(ticketData)
                .verifyComplete();
        
        // Cleanup
        StepVerifier.create(redisService.deleteKeyReactive("ticket:couple:" + coupleId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void testUserSessionStorage() {
        // Given
        String sessionId = "test-session-789";
        Object sessionData = Map.of(
            "userId", "user-123",
            "loginTime", System.currentTimeMillis(),
            "isActive", true
        );
        long ttlHours = 1;
        
        // When & Then (동기식)
        redisService.storeUserSession(sessionId, sessionData, ttlHours);
        
        // Cleanup
        redisService.deleteKey("session:" + sessionId);
    }

    @Test
    void testUserSessionStorageReactive() {
        // Given
        String sessionId = "test-session-reactive-101112";
        Object sessionData = Map.of(
            "userId", "user-reactive-456",
            "loginTime", System.currentTimeMillis(),
            "isActive", true
        );
        Duration ttl = Duration.ofHours(1);
        
        // When & Then (비동기식)
        StepVerifier.create(redisService.storeUserSessionReactive(sessionId, sessionData, ttl))
                .expectNext(true)
                .verifyComplete();
        
        // Cleanup
        StepVerifier.create(redisService.deleteKeyReactive("session:" + sessionId))
                .expectNext(true)
                .verifyComplete();
    }
}
