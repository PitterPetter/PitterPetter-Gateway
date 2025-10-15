package PitterPetter.loventure.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisConnectionLogger implements ApplicationListener<ApplicationReadyEvent> {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("🚀 Gateway 서비스 시작 - Redis 연결 상태 확인 중...");
        
        try {
            // Redis 연결 테스트
            String testKey = "health:check:" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, "OK", Duration.ofSeconds(10));
            String result = (String) redisTemplate.opsForValue().get(testKey);
            
            if ("OK".equals(result)) {
                log.info("✅ Redis 연결 성공 - 서비스 정상 시작");
                
                // 테스트 키 정리
                redisTemplate.delete(testKey);
                
                // Redis 정보 출력
                logRedisInfo();
                
            } else {
                log.error("❌ Redis 연결 실패 - 응답 값 불일치 (예상: OK, 실제: {})", result);
                throw new RuntimeException("Redis 응답 값 불일치");
            }
            
        } catch (Exception e) {
            log.error("❌ Redis 연결 실패: {}", e.getMessage());
            log.error("💥 서비스 시작 불가 - Redis 연결이 필요합니다");
            throw new RuntimeException("Redis 연결 실패로 서비스 시작 불가", e);
        }
    }
    
    private void logRedisInfo() {
        try {
            // Redis 서버 정보 조회 시도
            Properties info = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .serverCommands()
                    .info("server");
            
            if (info != null && !info.isEmpty()) {
                log.info("📊 Redis 서버 정보 - 버전: {}", info.getProperty("redis_version"));
            }
        } catch (Exception e) {
            log.warn("⚠️ Redis 서버 정보 조회 실패: {}", e.getMessage());
        }
    }
}

