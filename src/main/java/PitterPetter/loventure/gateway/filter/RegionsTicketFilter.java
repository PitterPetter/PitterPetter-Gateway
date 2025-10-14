package PitterPetter.loventure.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import PitterPetter.loventure.gateway.client.CouplesApiClient;
import PitterPetter.loventure.gateway.dto.TicketResponse;
import PitterPetter.loventure.gateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class RegionsTicketFilter implements GatewayFilter, Ordered {
    
    private static final Logger log = LoggerFactory.getLogger(RegionsTicketFilter.class);
    private final CouplesApiClient couplesApiClient;
    private final JwtUtil jwtUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        
        // /api/regions 요청인지 확인
        if (!path.startsWith("/api/regions")) {
            return chain.filter(exchange);
        }

        log.info("Processing regions request: {}", path);

        // JWT 토큰에서 coupleId 추출
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return onError(exchange, "인증 헤더가 없거나 형식이 올바르지 않습니다.", 
                          HttpStatus.UNAUTHORIZED, "AUTH_HEADER_INVALID", 
                          "Authorization 헤더가 Bearer 형식이 아닙니다.");
        }

        String token = authHeader.replace("Bearer ", "").trim();
        
        // JWT에서 coupleId 추출
        Claims claims = jwtUtil.extractClaims(token);
        Object coupleIdObj = claims.get("couple_id");
        if (coupleIdObj == null) {
            return onError(exchange, "JWT 토큰에 couple_id가 없습니다.", 
                          HttpStatus.UNAUTHORIZED, "MISSING_COUPLE_ID", 
                          "JWT 클레임에 couple_id가 포함되지 않았습니다.");
        }

        String coupleId = coupleIdObj.toString();
        String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        log.info("Processing request for couple_id: {}, correlation_id: {}", coupleId, correlationId);

        // Couples API에서 티켓 정보 조회
        return couplesApiClient.getTicketInfo(token, correlationId)
            .flatMap(ticketResponse -> {
                log.info("Ticket info retrieved for couple_id: {}, tickat: {}", 
                        coupleId, ticketResponse.getTickat());
                
                // 티켓 개수 확인
                if (ticketResponse.getTickat() == null || ticketResponse.getTickat() <= 0) {
                    log.warn("No tickets available for couple_id: {}", coupleId);
                    return onError(exchange, "티켓이 없습니다.", 
                                  HttpStatus.FORBIDDEN, "NO_TICKETS", 
                                  "보유한 티켓이 없어서 지역 정보를 조회할 수 없습니다.");
                }
                
                // 티켓 정보를 헤더에 추가
                exchange.getRequest().mutate()
                    .header("X-Couple-Ticket", String.valueOf(ticketResponse.getTickat()))
                    .header("X-Couple-ID", coupleId)
                    .header("X-Correlation-Id", correlationId)
                    .build();
                
                // Territory 서비스로 요청 전달
                return chain.filter(exchange);
            })
            .onErrorResume(error -> {
                log.error("Failed to retrieve ticket info for couple_id: {}, error: {}", 
                         coupleId, error.getMessage(), error);
                
                return onError(exchange, "티켓 정보를 가져오는데 실패했습니다.", 
                              HttpStatus.SERVICE_UNAVAILABLE, "TICKET_FETCH_FAILED", 
                              "Couples 서비스에서 티켓 정보를 가져올 수 없습니다.");
            });
    }

    private Mono<Void> onError(ServerWebExchange exchange, String message, 
                              HttpStatus status, String errorCode, String errorDetail) {
        log.error("RegionsTicketFilter Error: {} ({}) - {}", message, errorCode, errorDetail);

        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");

        String timestamp = java.time.OffsetDateTime.now().toString();

        String json = String.format(
            "{" +
                "\"timestamp\": \"%s\"," +
                "\"success\": false," +
                "\"message\": \"%s\"," +
                "\"code\": %d," +
                "\"error\": {\"code\": \"%s\", \"detail\": \"%s\"}" +
            "}",
            timestamp,
            message,
            status.value(),
            errorCode,
            errorDetail
        );

        var buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -10; // JWT 필터 이후, 라우팅 이전에 실행
    }
}
