package PitterPetter.loventure.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;

import PitterPetter.loventure.gateway.client.CouplesApiClient;
import PitterPetter.loventure.gateway.service.RedisService;
import PitterPetter.loventure.gateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Regions Unlock 필터
 * regions/unlock 경로로 들어오는 요청을 가로채서 티켓 상태를 검증하고
 * 허용/차단을 결정하는 필터
 */
@Component
@RequiredArgsConstructor
public class RegionsUnlockFilter implements GlobalFilter, Ordered {
    
    private static final Logger log = LoggerFactory.getLogger(RegionsUnlockFilter.class);
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final RedisService redisService;
    private final CouplesApiClient couplesApiClient;
    
    // 필터가 적용될 경로
    private static final String TARGET_PATH = "/api/regions/unlock";
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        String method = exchange.getRequest().getMethod().toString();
        
        // regions/unlock 경로가 아니면 필터 건너뛰기
        if (!path.equals(TARGET_PATH)) {
            return chain.filter(exchange);
        }
        
        log.info("🎫 RegionsUnlockFilter 시작 - {} : {}", method, path);
        
        try {
            // 1. JWT 토큰에서 userId, coupleId 추출
            String[] userInfo = extractUserInfoFromJwt(exchange);
            String userId = userInfo[0];
            String coupleId = userInfo[1];
            
            log.info("👤 사용자 정보 추출 완료 - userId: {}, coupleId: {}", userId, coupleId);
            
            // 2. Request Body에서 regions 정보 추출
            return extractRegionsFromBody(exchange)
                .flatMap(regions -> {
                    log.info("📍 지역 정보 추출 완료 - regions: {}", regions);
                    
                    // 3. Redis에서 티켓 정보 조회 및 검증
                    return validateTicketAndProcess(exchange, coupleId, regions)
                        .flatMap(isAllowed -> {
                            if (isAllowed) {
                                log.info("✅ 티켓 검증 통과 - regions/unlock 요청 허용");
                                return chain.filter(exchange);
                            } else {
                                log.warn("❌ 티켓 검증 실패 - regions/unlock 요청 차단");
                                return sendTicketErrorResponse(exchange);
                            }
                        });
                })
                .onErrorResume(error -> {
                    log.error("🚨 RegionsUnlockFilter 에러: {}", error.getMessage(), error);
                    return sendErrorResponse(exchange, "티켓 검증 중 오류가 발생했습니다.");
                });
                
        } catch (Exception e) {
            log.error("🚨 RegionsUnlockFilter 초기화 에러: {}", e.getMessage(), e);
            return sendErrorResponse(exchange, "티켓 검증 중 오류가 발생했습니다.");
        }
    }
    
    /**
     * JWT 토큰에서 userId, coupleId 추출
     */
    private String[] extractUserInfoFromJwt(ServerWebExchange exchange) throws Exception {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("유효하지 않은 Authorization 헤더");
        }
        
        String token = authHeader.replace("Bearer ", "").trim();
        if (!jwtUtil.isValidToken(token)) {
            throw new IllegalArgumentException("유효하지 않은 JWT 토큰");
        }
        
        Claims claims = jwtUtil.extractClaims(token);
        String userId = claims.get("user_id", String.class);
        String coupleId = claims.get("couple_id", String.class);
        
        if (userId == null || coupleId == null) {
            throw new IllegalArgumentException("JWT 토큰에 필요한 정보가 없습니다");
        }
        
        return new String[]{userId, coupleId};
    }
    
    /**
     * Request Body에서 regions 정보 추출
     */
    private Mono<String> extractRegionsFromBody(ServerWebExchange exchange) {
        return exchange.getRequest().getBody()
            .collectList()
            .flatMap(dataBuffers -> {
                byte[] bytes = new byte[dataBuffers.stream().mapToInt(DataBuffer::readableByteCount).sum()];
                int offset = 0;
                for (DataBuffer buffer : dataBuffers) {
                    int count = buffer.readableByteCount();
                    buffer.read(bytes, offset, count);
                    offset += count;
                }
                
                try {
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> bodyMap = objectMapper.readValue(body, Map.class);
                    String regions = (String) bodyMap.get("regions");
                    
                    if (regions == null || regions.trim().isEmpty()) {
                        return Mono.error(new IllegalArgumentException("regions 정보가 없습니다"));
                    }
                    
                    return Mono.just(regions);
                } catch (Exception e) {
                    return Mono.error(new IllegalArgumentException("Request Body 파싱 실패: " + e.getMessage()));
                }
            });
    }
    
    /**
     * 티켓 정보 검증 및 처리
     * Redis에서 coupleId로 티켓 정보를 조회하고 비즈니스 로직에 따라 허용/차단 결정
     */
    private Mono<Boolean> validateTicketAndProcess(ServerWebExchange exchange, String coupleId, String regions) {
        log.info("🔍 티켓 검증 시작 - coupleId: {}, regions: {}", coupleId, regions);
        
        try {
            // Redis에서 티켓 정보 조회 (동기식)
            Object ticketData = redisService.getCoupleTicketInfo(coupleId);
            
            if (ticketData == null) {
                log.warn("❌ Redis에 티켓 정보가 없음 - coupleId: {}", coupleId);
                return Mono.just(false);
            }
            
            // JSON 파싱하여 티켓 정보 추출
            @SuppressWarnings("unchecked")
            Map<String, Object> ticketMap = objectMapper.convertValue(ticketData, Map.class);
            
            int ticket = (Integer) ticketMap.get("ticket");
            String isTodayTicket = (String) ticketMap.get("isTodayTicket");
            String redisCoupleId = String.valueOf(ticketMap.get("coupleId")); // coupleId를 string으로 변환
            
            log.info("🎫 티켓 정보 - coupleId: {}, ticket: {}, isTodayTicket: {}", redisCoupleId, ticket, isTodayTicket);
            
            // JWT 토큰 추출 (비동기 API 호출용)
            String jwtToken = extractJwtTokenFromRequest(exchange);
            
            // 비즈니스 로직 처리
            return processTicketLogic(coupleId, ticketMap, ticket, isTodayTicket, jwtToken, redisCoupleId)
                .map(updatedTicketMap -> {
                    // Redis 업데이트 (동기식)
                    redisService.updateCoupleTicketInfo(coupleId, updatedTicketMap);
                    return true;
                });
                
        } catch (Exception e) {
            log.error("🚨 티켓 검증 중 오류 - coupleId: {}, error: {}", coupleId, e.getMessage(), e);
            return Mono.just(false);
        }
    }
    
    /**
     * 티켓 비즈니스 로직 처리
     * 케이스별로 티켓 상태를 검증하고 업데이트된 데이터 반환
     */
    private Mono<Map<String, Object>> processTicketLogic(String coupleId, Map<String, Object> ticketMap, 
                                                         int ticket, String isTodayTicket, String jwtToken, String redisCoupleId) {
        
        if ("true".equals(isTodayTicket)) {
            // 케이스 1: isTodayTicket = "true" → false로 변경하고 허용
            log.info("✅ 케이스 1: isTodayTicket을 false로 변경 - coupleId: {}", coupleId);
            
            Map<String, Object> updatedTicketMap = new java.util.HashMap<>(ticketMap);
            updatedTicketMap.put("coupleId", redisCoupleId); // coupleId를 string으로 저장
            updatedTicketMap.put("isTodayTicket", "false");
            updatedTicketMap.put("lastSyncedAt", java.time.OffsetDateTime.now().toString());
            
            // 비동기적으로 Couples API 호출
            scheduleAsyncCouplesApiUpdate(coupleId, updatedTicketMap, jwtToken);
            
            return Mono.just(updatedTicketMap);
            
        } else if ("false".equals(isTodayTicket)) {
            if (ticket > 0) {
                // 케이스 2: isTodayTicket = "false" + ticket > 0 → ticket 1 차감하고 허용
                log.info("✅ 케이스 2: ticket 1 차감 - coupleId: {}, ticket: {} → {}", coupleId, ticket, ticket - 1);
                
                Map<String, Object> updatedTicketMap = new java.util.HashMap<>(ticketMap);
                updatedTicketMap.put("coupleId", redisCoupleId); // coupleId를 string으로 저장
                updatedTicketMap.put("ticket", ticket - 1);
                updatedTicketMap.put("lastSyncedAt", java.time.OffsetDateTime.now().toString());
                
                // 비동기적으로 Couples API 호출
                scheduleAsyncCouplesApiUpdate(coupleId, updatedTicketMap, jwtToken);
                
                return Mono.just(updatedTicketMap);
                
            } else {
                // 케이스 3: isTodayTicket = "false" + ticket = 0 → 차단
                log.warn("❌ 케이스 3: 티켓 부족 - coupleId: {}, ticket: {}", coupleId, ticket);
                return Mono.error(new RuntimeException("티켓이 없습니다."));
            }
        } else {
            log.error("🚨 잘못된 isTodayTicket 값 - coupleId: {}, isTodayTicket: {}", coupleId, isTodayTicket);
            return Mono.error(new RuntimeException("잘못된 티켓 상태입니다."));
        }
    }
    
    /**
     * 비동기적으로 Couples API 호출 스케줄링
     * Reactor의 Mono.fromRunnable을 사용하여 백그라운드에서 API 호출
     */
    private void scheduleAsyncCouplesApiUpdate(String coupleId, Map<String, Object> ticketData, String jwtToken) {
        log.info("🔄 비동기 Couples API 호출 스케줄링 - coupleId: {}", coupleId);
        
        // Correlation ID 생성 (현재 시간 + coupleId로 고유성 보장)
        String correlationId = "regions-unlock-" + coupleId + "-" + System.currentTimeMillis();
        
        // 비동기적으로 Couples API 호출
        Mono.fromRunnable(() -> {
            if (jwtToken != null) {
                couplesApiClient.updateTicketInfo(jwtToken, ticketData, correlationId)
                    .subscribe(
                        response -> log.info("✅ 비동기 Couples API 호출 성공 - coupleId: {}, correlation_id: {}", 
                                           coupleId, correlationId),
                        error -> log.error("❌ 비동기 Couples API 호출 실패 - coupleId: {}, correlation_id: {}, error: {}", 
                                         coupleId, correlationId, error.getMessage())
                    );
            } else {
                log.warn("⚠️ JWT 토큰이 null이어서 비동기 API 호출 건너뜀 - coupleId: {}", coupleId);
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
          .subscribe();
    }
    
    /**
     * 현재 요청에서 JWT 토큰 추출
     * Authorization 헤더에서 Bearer 토큰을 추출
     */
    private String extractJwtTokenFromRequest(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.replace("Bearer ", "").trim();
        }
        return null;
    }
    
    /**
     * 티켓 부족 에러 응답 전송
     */
    private Mono<Void> sendTicketErrorResponse(ServerWebExchange exchange) {
        return sendErrorResponse(exchange, "티켓이 없습니다.");
    }
    
    /**
     * 에러 응답 전송
     */
    private Mono<Void> sendErrorResponse(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        
        String json = String.format(
            "{\"responseMessage\": \"%s\"}", 
            message
        );
        
        var buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
    
    @Override
    public int getOrder() {
        // JwtAuthorizationFilter(-1) 다음에 실행되도록 설정
        return 1;
    }
}
