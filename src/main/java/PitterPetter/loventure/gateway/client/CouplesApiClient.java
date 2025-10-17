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
        log.info("Calling Couples API for ticket info");
        
        return couplesWebClient
            .get()
            .uri("")
            .header("Authorization", "Bearer " + jwtToken)
            .header("Accept", "application/json")
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, response -> {
                log.warn("Couples API client error: {}", response.statusCode());
                return response.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(new CouplesApiException(
                        "Client error: " + body, HttpStatus.valueOf(response.statusCode().value()))));
            })
            .onStatus(HttpStatusCode::is5xxServerError, response -> {
                log.error("Couples API server error: {}", response.statusCode());
                return response.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(new CouplesApiException(
                        "Server error: " + body, HttpStatus.valueOf(response.statusCode().value()))));
            })
            .bodyToMono(TicketResponse.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(throwable -> throwable instanceof WebClientResponseException))
            .doOnSuccess(response -> {
                log.info("Successfully retrieved ticket info, ticket: {}", response.getTicket());
            })
            .doOnError(error -> {
                log.error("Failed to retrieve ticket info, error: {}", error.getMessage());
            });
    }
    
    // Write-Through 패턴으로 인해 PUT API 호출이 불필요
    // Redis Stream 이벤트를 통해 Auth Service가 자동으로 동기화됨
}
