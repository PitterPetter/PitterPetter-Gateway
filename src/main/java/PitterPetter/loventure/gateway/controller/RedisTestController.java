package PitterPetter.loventure.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import PitterPetter.loventure.gateway.service.RedisService;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/redis-test")
public class RedisTestController {
    
    private static final Logger log = LoggerFactory.getLogger(RedisTestController.class);
    
    @Autowired
    private RedisService redisService;
    
    /**
     * Redis 동기식 테스트
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> testRedisSync(@RequestParam String key, @RequestParam String value) {
        log.info("Redis 동기식 테스트 시작 - Key: {}, Value: {}", key, value);
        
        // 저장
        redisService.setValue(key, value);
        
        // 조회
        Object retrievedValue = redisService.getValue(key);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("key", key);
        response.put("originalValue", value);
        response.put("retrievedValue", retrievedValue);
        response.put("isMatch", value.equals(retrievedValue));
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Redis 비동기식 테스트 (WebFlux용)
     */
    @PostMapping("/reactive")
    public Mono<ResponseEntity<Map<String, Object>>> testRedisReactive(@RequestParam String key, @RequestParam String value) {
        log.info("Redis 비동기식 테스트 시작 - Key: {}, Value: {}", key, value);
        
        return redisService.setValueReactive(key, value)
                .then(redisService.getValueReactive(key))
                .map(retrievedValue -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("key", key);
                    response.put("originalValue", value);
                    response.put("retrievedValue", retrievedValue);
                    response.put("isMatch", value.equals(retrievedValue));
                    return ResponseEntity.ok(response);
                })
                .onErrorReturn(ResponseEntity.status(500).body(Map.of("success", false, "error", "Redis 작업 실패")));
    }
    
    /**
     * 티켓 정보 캐싱 테스트 (동기식)
     */
    @PostMapping("/cache-ticket-sync")
    public ResponseEntity<Map<String, Object>> cacheTicketSync(@RequestParam String coupleId, @RequestParam Integer ticketCount) {
        log.info("티켓 정보 캐싱 테스트 (동기식) - CoupleId: {}, TicketCount: {}", coupleId, ticketCount);
        
        Map<String, Object> ticketData = new HashMap<>();
        ticketData.put("coupleId", coupleId);
        ticketData.put("ticketCount", ticketCount);
        ticketData.put("cachedAt", System.currentTimeMillis());
        
        // 5분간 캐싱
        redisService.cacheTicketInfo(coupleId, ticketData, 5);
        
        // 캐시 조회
        Object cachedData = redisService.getCachedTicketInfo(coupleId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("coupleId", coupleId);
        response.put("cachedData", cachedData);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 티켓 정보 캐싱 테스트 (비동기식)
     */
    @PostMapping("/cache-ticket-reactive")
    public Mono<ResponseEntity<Map<String, Object>>> cacheTicketReactive(@RequestParam String coupleId, @RequestParam Integer ticketCount) {
        log.info("티켓 정보 캐싱 테스트 (비동기식) - CoupleId: {}, TicketCount: {}", coupleId, ticketCount);
        
        Map<String, Object> ticketData = new HashMap<>();
        ticketData.put("coupleId", coupleId);
        ticketData.put("ticketCount", ticketCount);
        ticketData.put("cachedAt", System.currentTimeMillis());
        
        // 5분간 캐싱 (비동기식)
        return redisService.cacheTicketInfoReactive(coupleId, ticketData, Duration.ofMinutes(5))
                .then(redisService.getCachedTicketInfoReactive(coupleId))
                .map(cachedData -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("coupleId", coupleId);
                    response.put("cachedData", cachedData);
                    return ResponseEntity.ok(response);
                })
                .onErrorReturn(ResponseEntity.status(500).body(Map.of("success", false, "error", "캐싱 실패")));
    }
    
    /**
     * Redis 연결 상태 확인
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> checkRedisHealth() {
        log.info("Redis 연결 상태 확인");
        
        return redisService.setValueReactive("health:check", "OK")
                .then(redisService.getValueReactive("health:check"))
                .map(value -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("redisStatus", "CONNECTED");
                    response.put("testValue", value);
                    return ResponseEntity.ok(response);
                })
                .onErrorReturn(ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "redisStatus", "DISCONNECTED",
                    "error", "Redis 연결 실패"
                )));
    }
}
