package PitterPetter.loventure.gateway.scheduler;

import PitterPetter.loventure.gateway.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class RedisDailyResetScheduler {
    
    private final RedisService redisService;
    
    @Value("${redis.daily-reset.enabled:true}")
    private boolean resetEnabled;
    
    @Value("${redis.daily-reset.mode:db}")
    private String resetMode; // "db" (현재 DB만) 또는 "all" (전체 Redis)
    
    /**
     * 매일 정각(00:00:00)에 Redis 초기화 실행
     * cron = "0 0 0 * * *" = 초 분 시 일 월 요일
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
            log.error("💥 스택 트레이스:", e);
        }
    }
    
    /**
     * 매일 정각 1분 후에 초기화 상태 확인
     */
    @Scheduled(cron = "0 1 0 * * *")
    public void verifyResetStatus() {
        if (!resetEnabled) {
            return;
        }
        
        try {
            // Redis 연결 상태 확인
            String testKey = "reset:verification:" + System.currentTimeMillis();
            redisService.setValue(testKey, "OK");
            Object result = redisService.getValue(testKey);
            
            if ("OK".equals(result)) {
                log.info("✅ Redis 일일 초기화 후 상태 확인 완료 - 정상 동작");
                redisService.deleteKey(testKey);
            } else {
                log.warn("⚠️ Redis 일일 초기화 후 상태 확인 실패 - 응답 값 불일치");
            }
            
        } catch (Exception e) {
            log.error("❌ Redis 일일 초기화 후 상태 확인 실패: {}", e.getMessage());
        }
    }
    
    /**
     * 수동으로 Redis 초기화 실행 (테스트용)
     */
    public void manualReset() {
        log.info("🔧 수동 Redis 초기화 실행");
        dailyRedisReset();
    }
    
    /**
     * 스케줄러 설정 정보 출력
     */
    public void logSchedulerInfo() {
        log.info("📅 Redis 일일 초기화 스케줄러 설정:");
        log.info("   - 활성화 상태: {}", resetEnabled ? "✅ 활성화" : "❌ 비활성화");
        log.info("   - 초기화 모드: {}", "all".equalsIgnoreCase(resetMode) ? "전체 Redis" : "현재 데이터베이스");
        log.info("   - 실행 시간: 매일 00:00:00");
        log.info("   - 상태 확인: 매일 00:01:00");
    }
}
