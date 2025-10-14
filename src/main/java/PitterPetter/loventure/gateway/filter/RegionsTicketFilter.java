package PitterPetter.loventure.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import PitterPetter.loventure.gateway.client.CouplesApiClient;
import PitterPetter.loventure.gateway.dto.TicketResponse;
import PitterPetter.loventure.gateway.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class RegionsTicketFilter implements GatewayFilter, Ordered {
    
    private static final Logger log = LoggerFactory.getLogger(RegionsTicketFilter.class);
    private final CouplesApiClient couplesApiClient;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        
        // /api/regions/unlock 요청인지 확인
        if (!path.equals("/api/regions/unlock")) {
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
        final String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id") != null 
            ? exchange.getRequest().getHeaders().getFirst("X-Correlation-Id")
            : UUID.randomUUID().toString();

        log.info("Processing request, correlation_id: {}", correlationId);

        // 요청 바디에서 regionId 파싱
        return exchange.getRequest().getBody()
            .collectList()
            .flatMap(dataBuffers -> {
                DataBuffer buffer = exchange.getRequest().getHeaders().getContentLength() > 0 
                    ? dataBuffers.get(0) : null;
                
                if (buffer == null) {
                    return onError(exchange, "요청 바디가 비어있습니다.", 
                                  HttpStatus.BAD_REQUEST, "EMPTY_BODY", 
                                  "regionId가 포함된 요청 바디가 필요합니다.");
                }
                
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                String body = new String(bytes, StandardCharsets.UTF_8);
                
                try {
                    JsonNode jsonNode = objectMapper.readTree(body);
                    String regionId = jsonNode.get("regionId").asText();
                    
                    log.info("Parsed regionId: {}, correlation_id: {}", regionId, correlationId);
                    
                    // Couples API에서 티켓 정보 조회
                    return couplesApiClient.getTicketInfo(token, correlationId)
                        .flatMap(ticketResponse -> {
                            String coupleId = ticketResponse.getCoupleId().toString();
                            log.info("Ticket info retrieved for couple_id: {}, tickat: {}", 
                                    coupleId, ticketResponse.getTickat());
                            
                            // 티켓 개수 확인
                            if (ticketResponse.getTickat() == null || ticketResponse.getTickat() <= 0) {
                                log.warn("No tickets available for couple_id: {}", coupleId);
                                return onError(exchange, "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.", 
                                              HttpStatus.TOO_MANY_REQUESTS, "42901", 
                                              "보유한 티켓이 없어서 지역을 해제할 수 없습니다.");
                            }
                            
                            // 티켓 차감 (tickat - 1)
                            int newTickat = ticketResponse.getTickat() - 1;
                            ticketResponse.setTickat(newTickat);
                            
                            log.info("Ticket deducted for couple_id: {}, new tickat: {}", coupleId, newTickat);
                            
                            // Territory 서비스로 요청 전달 (JWT 없이, 바디 수정)
                            String newBody = String.format("{\"coupleId\": \"%s\", \"regionId\": \"%s\"}", 
                                                          coupleId, regionId);
                            
                            // ServerHttpRequestDecorator로 바디 수정
                            ServerHttpRequestDecorator decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                                @Override
                                public Flux<DataBuffer> getBody() {
                                    DataBuffer buffer = exchange.getResponse().bufferFactory()
                                        .wrap(newBody.getBytes(StandardCharsets.UTF_8));
                                    return Flux.just(buffer);
                                }
                            };
                            
                            ServerWebExchange modifiedExchange = exchange.mutate()
                                .request(decoratedRequest)
                                .build();
                            
                            // JWT 토큰 제거, 필요한 헤더만 유지
                            modifiedExchange.getRequest().mutate()
                                .header(HttpHeaders.AUTHORIZATION, (String) null)  // JWT 토큰 제거
                                .header("X-Correlation-Id", correlationId)  // 상관관계 ID만 유지
                                .build();
                            
                            return chain.filter(modifiedExchange);
                        })
                        .onErrorResume(error -> {
                            log.error("Failed to retrieve ticket info, error: {}", 
                                     error.getMessage(), error);
                            
                            return onError(exchange, "티켓 정보를 가져오는데 실패했습니다.", 
                                          HttpStatus.SERVICE_UNAVAILABLE, "TICKET_FETCH_FAILED", 
                                          "Couples 서비스에서 티켓 정보를 가져올 수 없습니다.");
                        });
                        
                } catch (Exception e) {
                    log.error("Failed to parse request body, error: {}", e.getMessage());
                    return onError(exchange, "요청 바디 형식이 올바르지 않습니다.", 
                                  HttpStatus.BAD_REQUEST, "INVALID_BODY", 
                                  "regionId가 포함된 JSON 형식이어야 합니다.");
                }
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
