package PitterPetter.loventure.gateway.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    public Mono<TicketResponse> getTicketInfo(String jwtToken, String correlationId) {
        log.info("Calling Couples API for ticket info, correlation_id: {}", correlationId);
        
        return couplesWebClient
            .get()
            .uri("/api/couples/ticket")
            .header("Authorization", "Bearer " + jwtToken)
            .header("Accept", "application/json")
            .header("X-Correlation-Id", correlationId)
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
                log.info("Successfully retrieved ticket info, correlation_id: {}, tickat: {}", 
                        correlationId, response.getTicket());
            })
            .doOnError(error -> {
                log.error("Failed to retrieve ticket info, correlation_id: {}, error: {}", 
                         correlationId, error.getMessage());
            });
    }
}
