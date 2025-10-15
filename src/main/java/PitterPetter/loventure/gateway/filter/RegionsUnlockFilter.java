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
    
    // 필터가 적용될 경로
    private static final String TARGET_PATH = "/regions/unlock";
    
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
     * TODO: Redis 조회 및 비즈니스 로직 구현 예정
     */
    private Mono<Boolean> validateTicketAndProcess(ServerWebExchange exchange, String coupleId, String regions) {
        // 임시로 true 반환 (Redis 로직 구현 예정)
        log.info("🔍 티켓 검증 시작 - coupleId: {}, regions: {}", coupleId, regions);
        return Mono.just(true);
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
        // JwtAuthorizationFilter 다음에 실행되도록 설정
        return 0;
    }
}
