package PitterPetter.loventure.gateway.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import PitterPetter.loventure.gateway.dto.TicketResponse;
import PitterPetter.loventure.gateway.exception.CouplesApiException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class CouplesApiClient {
    
    private static final Logger log = LoggerFactory.getLogger(CouplesApiClient.class);
    private final WebClient couplesWebClient;

    public Mono<TicketResponse> getTicketInfo(String jwtToken) {
        log.info("🔄 Auth Service API 호출 시작 - Couples API for ticket info");
        log.debug("🔐 JWT 토큰 확인 - token length: {}", jwtToken != null ? jwtToken.length() : 0);
        

        //API 호출이 얼마나 오래 걸리는지 측정
        long startTime = System.currentTimeMillis();
        
        return couplesWebClient
            .get()
            .uri("")
            .header("Authorization", "Bearer " + jwtToken)
            .header("Accept", "application/json")
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, response -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.warn("❌ Couples API client error - status: {}, 응답시간: {}ms", response.statusCode(), responseTime);
                return response.bodyToMono(String.class)
                    .flatMap(body -> {
                        log.error("❌ Couples API 4xx 에러 상세 - status: {}, body: {}", response.statusCode(), body);
                        return Mono.error(new CouplesApiException(
                            "Client error: " + body, HttpStatus.valueOf(response.statusCode().value())));
                    });
                    //4xx 에러 발생 시 (400, 401, 403, 404 등)
                    //에러 응답 body를 읽어서 로그에 상세 정보 기록
            })
            .onStatus(HttpStatusCode::is5xxServerError, response -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.error("🚨 Couples API server error - status: {}, 응답시간: {}ms", response.statusCode(), responseTime);
                return response.bodyToMono(String.class)
                    .flatMap(body -> {
                        log.error("🚨 Couples API 5xx 에러 상세 - status: {}, body: {}", response.statusCode(), body);
                        return Mono.error(new CouplesApiException(
                            "Server error: " + body, HttpStatus.valueOf(response.statusCode().value())));
                    });
                    //5xx 에러 발생 시 (500, 502, 503, 504 등)
                    //에러 응답 body를 읽어서 로그에 상세 정보 기록
            })
            .bodyToMono(TicketResponse.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(throwable -> throwable instanceof WebClientResponseException))
            .doOnSuccess(response -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.info("✅ Auth Service API 호출 성공 - ticket: {}, 응답시간: {}ms", response.getTicket(), responseTime);
                log.debug("📋 TicketResponse 상세 - coupleId: {}, lastSyncedAt: {}", 
                         response.getCoupleId(), response.getLastSyncedAt());
            })
            
            .doOnError(error -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.error("❌ Auth Service API 호출 실패 - error: {}, 응답시간: {}ms", error.getMessage(), responseTime);
            });
    }
    
    // Write-Through 패턴으로 인해 PUT API 호출이 불필요
    // Redis Stream 이벤트를 통해 Auth Service가 자동으로 동기화됨
}
