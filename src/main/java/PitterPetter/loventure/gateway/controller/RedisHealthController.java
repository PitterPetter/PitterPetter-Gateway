package PitterPetter.loventure.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/actuator/redis")
@RequiredArgsConstructor
public class RedisHealthController {

    private final RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        log.debug("🔍 Redis 헬스 체크 시작");
        
        Map<String, Object> healthResponse = new HashMap<>();
        Instant startTime = Instant.now();
        
        try {
            String testKey = "health:check:" + System.currentTimeMillis();
            String testValue = "OK";
            
            // Redis 연결 및 쓰기 테스트
            redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));
            
            // Redis 읽기 테스트
            String retrievedValue = (String) redisTemplate.opsForValue().get(testKey);
            
            if (testValue.equals(retrievedValue)) {
                // 테스트 키 정리
                redisTemplate.delete(testKey);
                
                Duration duration = Duration.between(startTime, Instant.now());
                
                healthResponse.put("status", "UP");
                healthResponse.put("service", "Redis");
                healthResponse.put("operation", "Read/Write OK");
                healthResponse.put("responseTime", duration.toMillis() + "ms");
                healthResponse.put("timestamp", System.currentTimeMillis());
                
                log.debug("✅ Redis 헬스 체크 성공 - 응답시간: {}ms", duration.toMillis());
                
                return ResponseEntity.ok(healthResponse);
            } else {
                log.warn("⚠️ Redis 헬스 체크 실패 - 응답 값 불일치: expected={}, actual={}", testValue, retrievedValue);
                
                healthResponse.put("status", "DOWN");
                healthResponse.put("service", "Redis");
                healthResponse.put("error", "Data Mismatch");
                healthResponse.put("expected", testValue);
                healthResponse.put("actual", retrievedValue);
                healthResponse.put("timestamp", System.currentTimeMillis());
                
                return ResponseEntity.status(503).body(healthResponse);
            }
            
        } catch (Exception e) {
            Duration duration = Duration.between(startTime, Instant.now());
            
            log.error("❌ Redis 헬스 체크 실패: {} ({}ms)", e.getMessage(), duration.toMillis(), e);
            
            healthResponse.put("status", "DOWN");
            healthResponse.put("service", "Redis");
            healthResponse.put("error", e.getMessage());
            healthResponse.put("responseTime", duration.toMillis() + "ms");
            healthResponse.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(503).body(healthResponse);
        }
    }
    
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        log.debug("📊 Redis 정보 조회 시작");
        
        Map<String, Object> infoResponse = new HashMap<>();
        
        try {
            // Redis 연결 테스트
            String testKey = "info:check:" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, "INFO", Duration.ofSeconds(5));
            redisTemplate.delete(testKey);
            
            infoResponse.put("status", "Connected");
            infoResponse.put("service", "Redis");
            infoResponse.put("connectionFactory", redisTemplate.getConnectionFactory().getClass().getSimpleName());
            infoResponse.put("timestamp", System.currentTimeMillis());
            
            log.debug("✅ Redis 정보 조회 성공");
            
            return ResponseEntity.ok(infoResponse);
            
        } catch (Exception e) {
            log.error("❌ Redis 정보 조회 실패: {}", e.getMessage(), e);
            
            infoResponse.put("status", "Disconnected");
            infoResponse.put("service", "Redis");
            infoResponse.put("error", e.getMessage());
            infoResponse.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(503).body(infoResponse);
        }
    }
}
