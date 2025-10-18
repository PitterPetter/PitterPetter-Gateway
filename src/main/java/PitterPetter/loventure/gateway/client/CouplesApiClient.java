package PitterPetter.loventure.gateway.client;

import java.time.Duration;

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

@Service
@RequiredArgsConstructor
public class CouplesApiClient {
    
    private static final Logger log = LoggerFactory.getLogger(CouplesApiClient.class);
    private final WebClient couplesWebClient;

    public Mono<TicketResponse> getTicketInfo(String jwtToken) {
        log.info("🔄 Auth Service API 호출 시작 - Couples API for ticket info");
        log.debug("🔐 JWT 토큰 확인 - token length: {}", jwtToken != null ? jwtToken.length() : 0);
        log.info("🌐 WebClient 설정 확인 - couplesWebClient: {}", couplesWebClient != null ? "존재" : "null");
        log.info("📡 API 엔드포인트: GET / (루트 경로)");
        log.info("🔑 Authorization 헤더: Bearer {}", jwtToken != null ? jwtToken.substring(0, Math.min(20, jwtToken.length())) + "..." : "null");

        //API 호출이 얼마나 오래 걸리는지 측정
        long startTime = System.currentTimeMillis();
        log.info("⏱️ API 호출 시작 시간: {}", startTime);
        
        return couplesWebClient
            .get()
            .uri("")
            .header("Authorization", "Bearer " + jwtToken)
            .header("Accept", "application/json")
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, response -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.warn("❌ Couples API 4xx 클라이언트 에러 감지 - status: {}, 응답시간: {}ms", response.statusCode(), responseTime);
                log.warn("🔍 4xx 에러 상세 - statusCode: {}, reasonPhrase: {}", response.statusCode(), response.statusCode().toString());
                return response.bodyToMono(String.class)
                    .flatMap(body -> {
                        log.error("❌ Couples API 4xx 에러 상세 - status: {}, body: {}", response.statusCode(), body);
                        log.error("🚨 4xx 에러 응답 헤더: {}", response.headers().asHttpHeaders());
                        return Mono.error(new CouplesApiException(
                            "Client error: " + body, HttpStatus.valueOf(response.statusCode().value())));
                    });
                    //4xx 에러 발생 시 (400, 401, 403, 404 등)
                    //에러 응답 body를 읽어서 로그에 상세 정보 기록
            })
            .onStatus(HttpStatusCode::is5xxServerError, response -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.error("🚨 Couples API 5xx 서버 에러 감지 - status: {}, 응답시간: {}ms", response.statusCode(), responseTime);
                log.error("🔍 5xx 에러 상세 - statusCode: {}, reasonPhrase: {}", response.statusCode(), response.statusCode().toString());
                return response.bodyToMono(String.class)
                    .flatMap(body -> {
                        log.error("🚨 Couples API 5xx 에러 상세 - status: {}, body: {}", response.statusCode(), body);
                        log.error("🚨 5xx 에러 응답 헤더: {}", response.headers().asHttpHeaders());
                        return Mono.error(new CouplesApiException(
                            "Server error: " + body, HttpStatus.valueOf(response.statusCode().value())));
                    });
                    //5xx 에러 발생 시 (500, 502, 503, 504 등)
                    //에러 응답 body를 읽어서 로그에 상세 정보 기록
            })
            .bodyToMono(TicketResponse.class)
            .doOnSubscribe(subscription -> {
                log.info("📡 WebClient 구독 시작 - API 호출 준비 완료");
            })
            .doOnNext(response -> {
                log.info("📨 API 응답 수신 - TicketResponse 객체 생성됨");
                log.debug("📋 TicketResponse 내용 - ticket: {}, coupleId: {}, lastSyncedAt: {}", 
                         response.getTicket(), response.getCoupleId(), response.getLastSyncedAt());
            })
            .timeout(Duration.ofSeconds(5))
            .doOnCancel(() -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.warn("⏰ API 호출 타임아웃 - 5초 초과, 응답시간: {}ms", responseTime);
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .doBeforeRetry(retrySignal -> {
                    log.warn("🔄 API 재시도 시작 - 시도 횟수: {}, 에러: {}", 
                             retrySignal.totalRetries() + 1, retrySignal.failure().getMessage());
                })
                .filter(throwable -> {
                    log.debug("🔍 재시도 필터 체크 - throwable: {}, WebClientResponseException: {}", 
                             throwable.getClass().getSimpleName(), throwable instanceof WebClientResponseException);
                    return throwable instanceof WebClientResponseException;
                }))
            .doOnSuccess(response -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.info("✅ Auth Service API 호출 성공 - ticket: {}, 응답시간: {}ms", response.getTicket(), responseTime);
                log.info("📋 TicketResponse 상세 - coupleId: {}, lastSyncedAt: {}", 
                         response.getCoupleId(), response.getLastSyncedAt());
            })
            .doOnError(error -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.error("❌ Auth Service API 호출 실패 - error: {}, 응답시간: {}ms", error.getMessage(), responseTime);
                log.error("🚨 에러 타입: {}, 에러 클래스: {}", error.getClass().getSimpleName(), error.getClass().getName());
                if (error.getCause() != null) {
                    log.error("🔍 에러 원인: {}", error.getCause().getMessage());
                }
            })
            .doFinally(signalType -> {
                long responseTime = System.currentTimeMillis() - startTime;
                log.info("🏁 API 호출 완료 - signalType: {}, 총 소요시간: {}ms", signalType, responseTime);
            });
    }

    /**
     * Auth Service에서 티켓 차감 요청
     */
    public Mono<Boolean> consumeTicket(String coupleId, String jwtToken) {
        log.info("🎫 Auth Service에 티켓 차감 요청 - coupleId: {}", coupleId);
        
        return couplesWebClient
            .post()
            .uri("/{coupleId}/ticket/consume", coupleId)
            .header("Authorization", "Bearer " + jwtToken)
            .header("Accept", "application/json")
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, response -> {
                log.warn("티켓 차감 API client error: {}", response.statusCode());
                return response.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(new CouplesApiException(
                        "Client error: " + body, HttpStatus.valueOf(response.statusCode().value()))));
            })
            .onStatus(HttpStatusCode::is5xxServerError, response -> {
                log.error("티켓 차감 API server error: {}", response.statusCode());
                return response.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(new CouplesApiException(
                        "Server error: " + body, HttpStatus.valueOf(response.statusCode().value()))));
            })
            .bodyToMono(Boolean.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(throwable -> throwable instanceof WebClientResponseException))
            .doOnSuccess(response -> {
                log.info("✅ 티켓 차감 성공 - coupleId: {}, result: {}", coupleId, response);
            })
            .doOnError(error -> {
                log.error("❌ 티켓 차감 실패 - coupleId: {}, error: {}", coupleId, error.getMessage());
            });
    }
}
