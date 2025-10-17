package PitterPetter.loventure.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private final ObjectMapper objectMapper;
    private final RedisService redisService;
    private final CouplesApiClient couplesApiClient;
    
    // 필터가 적용될 경로
    private static final String TARGET_PATH = "/api/regions/unlock";
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        String method = exchange.getRequest().getMethod().toString();
        long startTime = System.currentTimeMillis();
        String requestId = String.valueOf(startTime);
        
        // regions/unlock 경로가 아니면 필터 건너뛰기
        if (!path.equals(TARGET_PATH)) {
            log.debug("🔍 RegionsUnlockFilter 건너뛰기 - path: {} (요청 ID: {})", path, requestId);
            return chain.filter(exchange);
        }
        
        log.info("🎫 RegionsUnlockFilter 시작 - method: {}, path: {} (요청 ID: {})", method, path, requestId);
        
        try {
            // 1. JWT 토큰에서 userId, coupleId 추출
            log.debug("🔐 JWT 토큰 파싱 시작 (요청 ID: {})", requestId);
            String[] userInfo = extractUserInfoFromJwt(exchange);
            String userId = userInfo[0];
            String coupleId = userInfo[1];
            
            log.info("👤 사용자 정보 추출 완료 - userId: {}, coupleId: {} (요청 ID: {})", userId, coupleId, requestId);
            
            // 2. Request Body에서 regions 정보 추출
            log.debug("📝 Request Body 파싱 시작 (요청 ID: {})", requestId);
            return extractRegionsFromBody(exchange)
                .flatMap(regions -> {
                    log.info("📍 지역 정보 추출 완료 - regions: {} (요청 ID: {})", regions, requestId);
                    
                    // 3. Redis에서 티켓 정보 조회 및 검증
                    log.debug("🔍 티켓 검증 프로세스 시작 - coupleId: {} (요청 ID: {})", coupleId, requestId);
                    return validateTicketAndProcess(exchange, coupleId, regions)
                        .flatMap(isAllowed -> {
                            long processingTime = System.currentTimeMillis() - startTime;
                            if (isAllowed) {
                                log.info("✅ 티켓 검증 통과 - regions/unlock 요청 허용 (처리시간: {}ms, 요청 ID: {})", processingTime, requestId);
                                return chain.filter(exchange);
                            } else {
                                log.warn("❌ 티켓 검증 실패 - regions/unlock 요청 차단 (처리시간: {}ms, 요청 ID: {})", processingTime, requestId);
                                return sendTicketErrorResponse(exchange);
                            }
                        });
                })
                .onErrorResume(error -> {
                    long processingTime = System.currentTimeMillis() - startTime;
                    log.error("🚨 RegionsUnlockFilter 에러 (처리시간: {}ms, 요청 ID: {}): {}", processingTime, requestId, error.getMessage(), error);
                    return sendErrorResponse(exchange, "티켓 검증 중 오류가 발생했습니다.");
                });
                
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            log.error("🚨 RegionsUnlockFilter 초기화 에러 (처리시간: {}ms, 요청 ID: {}): {}", processingTime, requestId, e.getMessage(), e);
            return sendErrorResponse(exchange, "티켓 검증 중 오류가 발생했습니다.");
        }
    }
    
    /**
     * JWT 토큰에서 userId, coupleId 추출 (Base64 직접 디코딩)
     */
    private String[] extractUserInfoFromJwt(ServerWebExchange exchange) throws Exception {
        log.debug("🔍 Authorization 헤더 확인 중");
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.error("❌ 유효하지 않은 Authorization 헤더 - header: {}", authHeader);
            throw new IllegalArgumentException("유효하지 않은 Authorization 헤더");
        }
        
        String token = authHeader.replace("Bearer ", "").trim();
        log.debug("🔐 JWT 토큰 추출 완료 - token length: {}", token.length());
        
        // JWT 토큰 구조 검증 (header.payload.signature)
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            log.error("❌ 유효하지 않은 JWT 토큰 형식 - parts length: {}", parts.length);
            throw new IllegalArgumentException("유효하지 않은 JWT 토큰 형식");
        }
        
        try {
            // Payload Base64 디코딩하여 Claims 추출
            log.debug("🔓 JWT payload 디코딩 시작");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            
            // 전체 claims 로깅으로 디버깅 강화
            log.debug("📋 JWT 전체 claims: {}", claims);
            
            String userId = (String) claims.get("userId");
            String coupleId = (String) claims.get("coupleId");
            
            log.debug("📋 JWT claims 추출 - userId: {}, coupleId: {}", userId, coupleId);
            
            // 더 상세한 에러 로깅
            if (userId == null) {
                log.error("❌ JWT 토큰에 userId가 없습니다 - 전체 claims: {}", claims);
                throw new IllegalArgumentException("JWT 토큰에 userId가 없습니다");
            }
            
            if (coupleId == null) {
                log.warn("⚠️ JWT 토큰에 coupleId가 없습니다 - 아직 커플 매칭이 안 된 상태일 수 있습니다");
                log.warn("⚠️ 전체 claims: {}", claims);
                log.warn("⚠️ 사용 가능한 필드들: {}", claims.keySet());
                throw new IllegalArgumentException("아직 커플 매칭이 완료되지 않았습니다. regions/unlock 기능을 사용하려면 먼저 커플 매칭을 완료해주세요.");
            }
            
            log.debug("✅ JWT 토큰 파싱 성공");
            return new String[]{userId, coupleId};
            
        } catch (Exception e) {
            log.error("❌ JWT 토큰 디코딩 실패: {}", e.getMessage());
            throw new IllegalArgumentException("JWT 토큰 디코딩 실패: " + e.getMessage());
        }
    }
    
    /**
     * Request Body에서 regions 정보 추출
     */
    private Mono<String> extractRegionsFromBody(ServerWebExchange exchange) {
        log.debug("📝 Request Body 읽기 시작");
        return exchange.getRequest().getBody()
            .collectList()
            .flatMap(dataBuffers -> {
                log.debug("📦 DataBuffer 수집 완료 - count: {}", dataBuffers.size());
                byte[] bytes = new byte[dataBuffers.stream().mapToInt(DataBuffer::readableByteCount).sum()];
                int offset = 0;
                for (DataBuffer buffer : dataBuffers) {
                    int count = buffer.readableByteCount();
                    buffer.read(bytes, offset, count);
                    offset += count;
                }
                
                log.debug("📄 Request Body 크기: {} bytes", bytes.length);
                
                try {
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    log.debug("📋 Request Body 내용: {}", body);
                    
                    @SuppressWarnings("unchecked")
                    Map<String, Object> bodyMap = objectMapper.readValue(body, Map.class);
                    String regions = (String) bodyMap.get("regions");
                    
                    log.debug("📍 regions 값 추출: {}", regions);
                    
                    if (regions == null || regions.trim().isEmpty()) {
                        log.error("❌ regions 정보가 없습니다 - regions: {}", regions);
                        return Mono.error(new IllegalArgumentException("regions 정보가 없습니다"));
                    }
                    
                    log.debug("✅ regions 정보 추출 성공");
                    return Mono.just(regions);
                } catch (Exception e) {
                    log.error("❌ Request Body 파싱 실패: {}", e.getMessage());
                    return Mono.error(new IllegalArgumentException("Request Body 파싱 실패: " + e.getMessage()));
                }
            });
    }
    
    /**
     * 티켓 정보 검증 및 처리
     * Redis에서 coupleId로 티켓 정보를 조회하고 비즈니스 로직에 따라 허용/차단 결정
     * Redis 캐시 미스 시 Auth Service에서 데이터를 가져와서 Redis에 캐싱
     */
    private Mono<Boolean> validateTicketAndProcess(ServerWebExchange exchange, String coupleId, String regions) {
        log.info("🔍 티켓 검증 시작 - coupleId: {}, regions: {}", coupleId, regions);
        
        try {
            // Redis에서 티켓 정보 조회 (동기식)
            log.debug("🔍 Redis에서 티켓 정보 조회 시작 - coupleId: {}", coupleId);
            Object ticketData = redisService.getCoupleTicketInfo(coupleId);
            
            if (ticketData == null) {
                log.warn("❌ Redis 캐시 미스 - coupleId: {}", coupleId);
                log.info("🔄 Auth Service에서 티켓 정보 조회 시작 - coupleId: {}", coupleId);
                // Redis 캐시 미스 시 Auth Service에서 데이터 가져오기
                return fetchTicketFromAuthServiceAndCache(exchange, coupleId)
                    .flatMap(fetchedTicketData -> {
                        if (fetchedTicketData != null) {
                            log.info("✅ Auth Service에서 티켓 정보 조회 성공 - coupleId: {}", coupleId);
                            return processTicketLogicWithData(coupleId, fetchedTicketData, exchange);
                        } else {
                            log.error("❌ Auth Service에서 티켓 정보 조회 실패 - coupleId: {}", coupleId);
                            return Mono.just(false);
                        }
                    });
            }
            
            log.info("✅ Redis 캐시 히트 - coupleId: {}", coupleId);
            // Redis에 데이터가 있는 경우 기존 로직 처리
            return processTicketLogicWithData(coupleId, ticketData, exchange);
                
        } catch (Exception e) {
            log.error("🚨 티켓 검증 중 오류 - coupleId: {}, error: {}", coupleId, e.getMessage(), e);
            return Mono.just(false);
        }
    }
    
    /**
     * Auth Service에서 티켓 정보를 가져와서 Redis에 캐싱
     * Write-Through 패턴의 캐시 미스 처리
     */
    private Mono<Object> fetchTicketFromAuthServiceAndCache(ServerWebExchange exchange, String coupleId) {
        log.info("🔄 Auth Service에서 티켓 정보 조회 시작 - coupleId: {}", coupleId);
        
        String jwtToken = extractJwtTokenFromRequest(exchange);
        if (jwtToken == null) {
            log.error("❌ JWT 토큰이 없어서 Auth Service 호출 불가 - coupleId: {}", coupleId);
            return Mono.empty();
        }
        
        log.debug("🔐 JWT 토큰 확인 완료 - token length: {}", jwtToken.length());
        log.debug("📡 CouplesApiClient.getTicketInfo 호출 시작");
        
        return couplesApiClient.getTicketInfo(jwtToken)
            .map(ticketResponse -> {
                log.debug("📋 TicketResponse 수신 - ticket: {}, lastSyncedAt: {}", 
                         ticketResponse.getTicket(), ticketResponse.getLastSyncedAt());
                
                // TicketResponse를 Map으로 변환
                Map<String, Object> ticketData = Map.of(
                    "coupleId", coupleId,
                    "ticket", ticketResponse.getTicket(),
                    "lastSyncedAt", ticketResponse.getLastSyncedAt()
                );
                
                log.info("✅ Auth Service에서 티켓 정보 조회 성공 - coupleId: {}, ticket: {}", 
                        coupleId, ticketResponse.getTicket());
                
                // Redis에 캐싱 (Write-Through 패턴 적용)
                log.debug("💾 Redis에 티켓 정보 캐싱 시작 - coupleId: {}", coupleId);
                redisService.updateCoupleTicketInfo(coupleId, ticketData);
                
                return (Object) ticketData;
            })
            .doOnError(error -> log.error("❌ Auth Service 티켓 정보 조회 실패 - coupleId: {}, error: {}", 
                                         coupleId, error.getMessage()))
            .onErrorReturn(null);
    }
    
    /**
     * 티켓 데이터로 비즈니스 로직 처리
     * 공통 로직을 별도 메서드로 분리
     */
    private Mono<Boolean> processTicketLogicWithData(String coupleId, Object ticketData, ServerWebExchange exchange) {
        log.debug("🔍 티켓 데이터 처리 시작 - coupleId: {}", coupleId);
        
        try {
            // JSON 파싱하여 티켓 정보 추출
            log.debug("📋 티켓 데이터 JSON 파싱 시작");
            @SuppressWarnings("unchecked")
            Map<String, Object> ticketMap = objectMapper.convertValue(ticketData, Map.class);
            
            int ticket = (Integer) ticketMap.get("ticket");
            String redisCoupleId = String.valueOf(ticketMap.get("coupleId")); // coupleId를 string으로 변환
            
            log.info("🎫 티켓 정보 - coupleId: {}, ticket: {}", redisCoupleId, ticket);
            log.debug("📊 티켓 상세 정보 - ticketMap: {}", ticketMap);
            
            // JWT 토큰 추출 (비동기 API 호출용)
            String jwtToken = extractJwtTokenFromRequest(exchange);
            log.debug("🔐 JWT 토큰 추출 완료 - token length: {}", jwtToken != null ? jwtToken.length() : 0);
            
            // 비즈니스 로직 처리
            log.debug("⚙️ 티켓 비즈니스 로직 처리 시작");
            return processTicketLogic(coupleId, ticketMap, ticket, jwtToken, redisCoupleId)
                .map(updatedTicketMap -> {
                    log.debug("💾 Redis 티켓 정보 업데이트 시작 - coupleId: {}", coupleId);
                    // Redis 업데이트 (Write-Through 패턴이 자동으로 적용됨)
                    redisService.updateCoupleTicketInfo(coupleId, updatedTicketMap);
                    log.debug("✅ Redis 티켓 정보 업데이트 완료 - coupleId: {}", coupleId);
                    return true;
                });
                
        } catch (Exception e) {
            log.error("🚨 티켓 데이터 처리 중 오류 - coupleId: {}, error: {}", coupleId, e.getMessage(), e);
            return Mono.just(false);
        }
    }
    
    /**
     * 티켓 비즈니스 로직 처리
     * 티켓이 있으면 1 차감하고 허용, 없으면 차단
     * Write-Through 패턴으로 인해 별도의 API 호출이 불필요
     */
    private Mono<Map<String, Object>> processTicketLogic(String coupleId, Map<String, Object> ticketMap, 
                                                         int ticket, String jwtToken, String redisCoupleId) {
        
        log.debug("🎫 티켓 비즈니스 로직 검증 - ticket: {}", ticket);
        
        if (ticket > 0) {
            // 티켓 1 차감하고 허용
            log.info("✅ 티켓 1 차감 - coupleId: {}, ticket: {} → {}", coupleId, ticket, ticket - 1);
            
            Map<String, Object> updatedTicketMap = new java.util.HashMap<>(ticketMap);
            updatedTicketMap.put("coupleId", redisCoupleId); // coupleId를 string으로 저장
            updatedTicketMap.put("ticket", ticket - 1);
            updatedTicketMap.put("lastSyncedAt", java.time.OffsetDateTime.now().toString());
            
            log.debug("📊 업데이트된 티켓 정보 - updatedTicketMap: {}", updatedTicketMap);
            
            // Write-Through 패턴으로 자동 동기화됨 (별도 API 호출 불필요)
            log.info("🔄 Write-Through 패턴으로 Auth Service 자동 동기화 예정 - coupleId: {}", coupleId);
            
            return Mono.just(updatedTicketMap);
            
        } else {
            // 티켓 부족으로 차단
            log.warn("❌ 티켓 부족 - coupleId: {}, ticket: {}", coupleId, ticket);
            log.debug("🚫 regions/unlock 요청 차단 - 티켓 부족");
            return Mono.error(new RuntimeException("티켓이 없습니다."));
        }
    }
    
    // Write-Through 패턴으로 인해 별도의 비동기 API 호출이 불필요
    // Redis Stream 이벤트를 통해 Auth Service가 자동으로 동기화됨
    
    /**
     * 현재 요청에서 JWT 토큰 추출
     * Authorization 헤더에서 Bearer 토큰을 추출 (Base64 디코딩 방식)
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
