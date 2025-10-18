package PitterPetter.loventure.gateway.config;

import java.time.Duration;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import PitterPetter.loventure.gateway.scheduler.RedisDailyResetScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisConnectionLogger implements ApplicationListener<ApplicationReadyEvent> {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private RedisDailyResetScheduler redisDailyResetScheduler;
    
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
                
                // 스케줄러 정보 출력
                redisDailyResetScheduler.logSchedulerInfo();
                
            } else {
                log.error("❌ Redis 연결 실패 - 응답 값 불일치 (예상: OK, 실제: {})", result);
                log.warn("⚠️ Redis 없이 서비스 계속 실행 (일부 기능 제한)");
            }
            
        } catch (Exception e) {
            log.warn("⚠️ Redis 연결 실패: {} - Redis 없이 서비스 계속 실행", e.getMessage());
            log.info("ℹ️ 일부 기능(캐싱, 스케줄러)이 제한됩니다");
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

