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
        log.debug("🌐 Request Headers: {}", exchange.getRequest().getHeaders());
        log.debug("🔗 Request URI: {}", exchange.getRequest().getURI());
        log.debug("📡 Remote Address: {}", exchange.getRequest().getRemoteAddress());
        
        try {
            // 1. JwtAuthorizationFilter에서 파싱한 정보를 attributes에서 가져오기
            log.debug("🔍 ServerWebExchange attributes에서 사용자 정보 조회 시작 (요청 ID: {})", requestId);
            log.debug("📋 현재 attributes 상태: {}", exchange.getAttributes());
            
            String userId = exchange.getAttribute("userId");
            String coupleId = exchange.getAttribute("coupleId");
            
            log.info("👤 사용자 정보 조회 완료 - userId: {}, coupleId: {} (요청 ID: {})", userId, coupleId, requestId);
            log.debug("🔍 attributes 조회 결과 - userId 존재: {}, coupleId 존재: {}", userId != null, coupleId != null);
            log.debug("📋 전체 attributes 키 목록: {}", exchange.getAttributes().keySet());
            log.debug("🔍 attributes 상세 내용: {}", exchange.getAttributes());
            
            // 사용자 정보 검증
            if (userId == null) {
                log.error("❌ ServerWebExchange attributes에 userId가 없습니다 (요청 ID: {})", requestId);
                throw new IllegalArgumentException("사용자 정보가 없습니다. 인증이 필요합니다.");
            }
            
            if (coupleId == null) {
                log.warn("⚠️ ServerWebExchange attributes에 coupleId가 없습니다 - 아직 커플 매칭이 안 된 상태일 수 있습니다 (요청 ID: {})", requestId);
                throw new IllegalArgumentException("아직 커플 매칭이 완료되지 않았습니다. regions/unlock 기능을 사용하려면 먼저 커플 매칭을 완료해주세요.");
            }
    
            // 2. Request Body에서 regions 정보 추출
            log.debug("📝 Request Body 파싱 시작 (요청 ID: {})", requestId);
            log.debug("📊 Request Body Content-Type: {}", exchange.getRequest().getHeaders().getContentType());
            log.debug("📏 Request Body Content-Length: {}", exchange.getRequest().getHeaders().getContentLength());
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
     * Request Body에서 regions 정보 추출
     * - {"regions": [...]} 또는 ["a","b"] 형태 모두 지원
     */
    private Mono<String> extractRegionsFromBody(ServerWebExchange exchange) {
        log.debug("📝 Request Body 읽기 시작");
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
                    log.info("📋 Request Body 원본 내용: {}", body);
                    log.info("📊 Request Body 길이: {} characters", body.length());
                    log.info("🔍 Request Body 첫 200자: {}", body.length() > 200 ? body.substring(0, 200) + "..." : body);

                    // --- case 1: JSON 배열 ---
                    if (body.trim().startsWith("[")) {
                        log.info("🔍 Case 1: Body가 JSON 배열로 감지됨");
                        log.info("📊 배열 시작 확인: body.trim().startsWith(\"[\") = {}", body.trim().startsWith("["));
                        java.util.List<?> list = objectMapper.readValue(body, java.util.List.class);
                        log.info("✅ 배열 파싱 완료 - {} items", list.size());
                        log.info("📋 배열 내용: {}", list);
                        String regions = objectMapper.writeValueAsString(list);
                        log.info("📍 regions(JSON): {}", regions);
                        return Mono.just(regions);
                    }

                    // --- case 2: JSON 객체 ---
                    log.info("🔍 Case 2: JSON 객체로 처리 시작");
                    Map<String, Object> bodyMap = objectMapper.readValue(body, Map.class);
                    log.info("📋 bodyMap 전체 내용: {}", bodyMap);
                    log.info("📊 bodyMap 키 목록: {}", bodyMap.keySet());
                    
                    Object regionsObj = bodyMap.get("regions");
                    log.info("📍 regionsObj 존재 여부: {}", regionsObj != null);
                    log.info("📍 regionsObj 타입: {}", regionsObj != null ? regionsObj.getClass().getName() : "null");
                    log.info("📍 regionsObj 값: {}", regionsObj);
                    
                    if (regionsObj == null) {
                        log.error("❌ regions 필드가 없습니다 - bodyMap: {}", bodyMap);
                        return Mono.error(new IllegalArgumentException("Request body에 'regions' 필드가 없습니다."));
                    }

                    String regions;
                    log.info("🔍 regionsObj 타입 체크 시작");
                    log.info("📊 instanceof java.util.List: {}", regionsObj instanceof java.util.List);
                    log.info("📊 instanceof String: {}", regionsObj instanceof String);
                    
                    if (regionsObj instanceof java.util.List) {
                        log.info("✅ ArrayList 감지 - JSON 문자열로 변환 시작");
                        regions = objectMapper.writeValueAsString(regionsObj);
                        log.info("✅ ArrayList → JSON 문자열 변환 완료: {}", regions);
                    } else if (regionsObj instanceof String) {
                        log.info("✅ String 감지 - 그대로 사용");
                        regions = (String) regionsObj;
                        log.info("✅ String 그대로 사용: {}", regions);
                    } else {
                        log.error("❌ 지원되지 않는 타입 - 타입: {}, 값: {}", regionsObj.getClass().getName(), regionsObj);
                        return Mono.error(new IllegalArgumentException("regions 필드의 타입이 올바르지 않습니다."));
                    }

                    log.info("✅ regions 최종 추출 완료: {}", regions);
                    log.info("📊 regions 최종 타입: {}", regions.getClass().getSimpleName());
                    return Mono.just(regions);

                } catch (Exception e) {
                    log.error("❌ Request Body 파싱 실패: {}", e.getMessage());
                    log.error("❌ 에러 스택 트레이스:", e);
                    log.error("❌ Request Body 원본: {}", new String(bytes, StandardCharsets.UTF_8));
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
        log.debug("📊 티켓 검증 파라미터 상세:");
        log.debug("  - coupleId 타입: {}, 길이: {}", coupleId.getClass().getSimpleName(), coupleId.length());
        log.debug("  - regions 타입: {}, 길이: {}", regions.getClass().getSimpleName(), regions.length());
        log.debug("  - regions 내용: {}", regions);
        
        try {
            // Redis에서 티켓 정보 조회 (동기식)
            log.debug("🔍 Redis에서 티켓 정보 조회 시작 - coupleId: {}", coupleId);
            log.debug("📊 Redis 조회 전 상태:");
            log.debug("  - coupleId: {}", coupleId);
            log.debug("  - Redis 키 예상값: coupleId:{}", coupleId);
            
            long redisStartTime = System.currentTimeMillis();
            Object ticketData = redisService.getCoupleTicketInfo(coupleId);
            long redisQueryTime = System.currentTimeMillis() - redisStartTime;
            
            log.debug("⏱️ Redis 조회 시간: {}ms", redisQueryTime);
            log.debug("📊 Redis 조회 결과: {}", ticketData != null ? "데이터 존재" : "데이터 없음");
            
            if (ticketData == null) {
                log.warn("❌ Redis 캐시 미스 - coupleId: {}", coupleId);
                log.warn("📊 Redis 캐시 미스 상세:");
                log.warn("  - 조회 시간: {}ms", redisQueryTime);
                log.warn("  - Redis 키: coupleId:{}", coupleId);
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
            log.info("📊 Redis 캐시 히트 상세:");
            log.info("  - 조회 시간: {}ms", redisQueryTime);
            log.info("  - 데이터 타입: {}", ticketData.getClass().getSimpleName());
            log.debug("  - 데이터 내용: {}", ticketData);
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
        log.debug("📊 Auth Service 호출 상세:");
        log.debug("  - coupleId: {}", coupleId);
        log.debug("  - 호출 시간: {}", java.time.OffsetDateTime.now());
        
        String jwtToken = extractJwtTokenFromRequest(exchange);
        log.debug("🔐 JWT 토큰 추출 결과:");
        log.debug("  - 토큰 존재: {}", jwtToken != null);
        log.debug("  - 토큰 길이: {}", jwtToken != null ? jwtToken.length() : 0);
        
        if (jwtToken == null) {
            log.error("❌ JWT 토큰이 없어서 Auth Service 호출 불가 - coupleId: {}", coupleId);
            log.error("🔍 Authorization 헤더 확인: {}", exchange.getRequest().getHeaders().getFirst("Authorization"));
            return Mono.empty();
        }
        
        log.debug("🔐 JWT 토큰 확인 완료 - token length: {}", jwtToken.length());
        log.debug("📡 CouplesApiClient.getTicketInfo 호출 시작");
        log.debug("📊 API 호출 상세:");
        log.debug("  - JWT 토큰 앞 20자: {}", jwtToken.substring(0, Math.min(20, jwtToken.length())));
        log.debug("  - JWT 토큰 뒤 20자: {}", jwtToken.substring(Math.max(0, jwtToken.length() - 20)));
        
        return couplesApiClient.getTicketInfo(jwtToken)
            .map(ticketResponse -> {
                log.debug("📋 TicketResponse 수신 - ticket: {}, lastSyncedAt: {}", 
                         ticketResponse.getTicket(), ticketResponse.getLastSyncedAt());
                log.debug("📊 TicketResponse 상세 정보:");
                log.debug("  - ticket 타입: {}", ticketResponse.getTicket().getClass().getSimpleName());
                log.debug("  - lastSyncedAt 타입: {}", ticketResponse.getLastSyncedAt().getClass().getSimpleName());
                log.debug("  - TicketResponse 전체: {}", ticketResponse);
                
                // TicketResponse를 Map으로 변환
                log.debug("🔄 TicketResponse를 Map으로 변환 시작");
                Map<String, Object> ticketData = Map.of(
                    "coupleId", coupleId,
                    "ticket", ticketResponse.getTicket(),
                    "lastSyncedAt", ticketResponse.getLastSyncedAt()
                );
                log.debug("📊 변환된 ticketData: {}", ticketData);
                
                log.info("✅ Auth Service에서 티켓 정보 조회 성공 - coupleId: {}, ticket: {}", 
                        coupleId, ticketResponse.getTicket());
                
                // Redis에 캐싱 (Write-Through 패턴 적용)
                log.debug("💾 Redis에 티켓 정보 캐싱 시작 - coupleId: {}", coupleId);
                log.debug("📊 캐싱할 데이터: {}", ticketData);
                long cacheStartTime = System.currentTimeMillis();
                redisService.updateCoupleTicketInfo(coupleId, ticketData);
                long cacheTime = System.currentTimeMillis() - cacheStartTime;
                log.debug("⏱️ Redis 캐싱 시간: {}ms", cacheTime);
                
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
        log.debug("📊 입력 데이터 상세:");
        log.debug("  - coupleId: {}", coupleId);
        log.debug("  - ticketData 타입: {}", ticketData.getClass().getSimpleName());
        log.debug("  - ticketData 내용: {}", ticketData);
        
        try {
            // JSON 파싱하여 티켓 정보 추출
            log.debug("📋 티켓 데이터 JSON 파싱 시작");
            log.debug("📊 파싱 전 ticketData: {}", ticketData);
            @SuppressWarnings("unchecked")
            Map<String, Object> ticketMap = objectMapper.convertValue(ticketData, Map.class);
            log.debug("📊 파싱 후 ticketMap: {}", ticketMap);
            
            int ticket = (Integer) ticketMap.get("ticket");
            String redisCoupleId = String.valueOf(ticketMap.get("coupleId")); // coupleId를 string으로 변환
            
            log.info("🎫 티켓 정보 - coupleId: {}, ticket: {}", redisCoupleId, ticket);
            log.debug("📊 티켓 상세 정보 - ticketMap: {}", ticketMap);
            log.debug("📊 티켓 필드 상세:");
            log.debug("  - ticket 타입: {}, 값: {}", ticketMap.get("ticket").getClass().getSimpleName(), ticket);
            log.debug("  - coupleId 타입: {}, 값: {}", ticketMap.get("coupleId").getClass().getSimpleName(), redisCoupleId);
            log.debug("  - lastSyncedAt: {}", ticketMap.get("lastSyncedAt"));
            
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
        log.debug("📊 비즈니스 로직 입력 파라미터:");
        log.debug("  - coupleId: {}", coupleId);
        log.debug("  - ticket: {} (타입: int)", ticket);
        log.debug("  - redisCoupleId: {}", redisCoupleId);
        log.debug("  - jwtToken 존재: {}", jwtToken != null);
        
        if (ticket > 0) {
            // 티켓 1 차감하고 허용
            log.info("✅ 티켓 1 차감 - coupleId: {}, ticket: {} → {}", coupleId, ticket, ticket - 1);
            log.debug("📊 티켓 차감 상세:");
            log.debug("  - 현재 티켓: {}", ticket);
            log.debug("  - 차감 후 티켓: {}", ticket - 1);
            log.debug("  - 차감량: 1");
            
            Map<String, Object> updatedTicketMap = new java.util.HashMap<>(ticketMap);
            log.debug("🔄 티켓 정보 업데이트 시작");
            log.debug("📊 업데이트 전 ticketMap: {}", ticketMap);
            
            updatedTicketMap.put("coupleId", redisCoupleId); // coupleId를 string으로 저장
            updatedTicketMap.put("ticket", ticket - 1);
            updatedTicketMap.put("lastSyncedAt", java.time.OffsetDateTime.now().toString());
            
            log.debug("📊 업데이트 후 updatedTicketMap: {}", updatedTicketMap);
            
            log.debug("📊 업데이트된 티켓 정보 - updatedTicketMap: {}", updatedTicketMap);
            log.debug("📊 업데이트 상세:");
            log.debug("  - coupleId: {} → {}", ticketMap.get("coupleId"), updatedTicketMap.get("coupleId"));
            log.debug("  - ticket: {} → {}", ticketMap.get("ticket"), updatedTicketMap.get("ticket"));
            log.debug("  - lastSyncedAt: {} → {}", ticketMap.get("lastSyncedAt"), updatedTicketMap.get("lastSyncedAt"));
            
            // Write-Through 패턴으로 자동 동기화됨 (별도 API 호출 불필요)
            log.info("🔄 Write-Through 패턴으로 Auth Service 자동 동기화 예정 - coupleId: {}", coupleId);
            
            return Mono.just(updatedTicketMap);
            
        } else {
            // 티켓 부족으로 차단
            log.warn("❌ 티켓 부족 - coupleId: {}, ticket: {}", coupleId, ticket);
            log.debug("🚫 regions/unlock 요청 차단 - 티켓 부족");
            log.debug("📊 티켓 부족 상세:");
            log.debug("  - 현재 티켓: {}", ticket);
            log.debug("  - 필요한 티켓: 1");
            log.debug("  - 부족한 티켓: {}", 1 - ticket);
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
        log.debug("🔐 JWT 토큰 추출 시작");
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        log.debug("📊 Authorization 헤더:");
        log.debug("  - 존재 여부: {}", authHeader != null);
        log.debug("  - 내용: {}", authHeader);
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.replace("Bearer ", "").trim();
            log.debug("✅ JWT 토큰 추출 성공 - 길이: {}", token.length());
            return token;
        }
        
        log.debug("❌ JWT 토큰 추출 실패 - Bearer 토큰 없음");
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
