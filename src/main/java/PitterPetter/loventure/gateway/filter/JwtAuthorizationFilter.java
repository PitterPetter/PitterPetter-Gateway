package PitterPetter.loventure.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import PitterPetter.loventure.gateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JwtAuthorizationFilter implements GlobalFilter, Ordered { // filter 메서드
	private static final Logger log = LoggerFactory.getLogger(JwtAuthorizationFilter.class); // 요청 경로, 인증 여부, 에러 등 로깅
	private final JwtUtil jwtUtil;

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String path = exchange.getRequest().getPath().toString(); // 요청 URI 가져옴
		String method = exchange.getRequest().getMethod().toString();

		// 1. 요청 로그
		log.info("{} : {}", method, path);

		if (isPublic(path)) { // isPublic(path)에서 정의한 리스트에 해당하면 인증 검사 건너뜀
			return chain.filter(exchange);
		}

		// Authorization 헤더 검사
		String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (authHeader == null) { // Authorization 헤더 존재 여부 확인
			return onError(exchange, "인증 헤더가 없습니다.", HttpStatus.UNAUTHORIZED, "AUTH_HEADER_MISSING", "Authorization 헤더가 요청에 포함되지 않았습니다.");
		}
		if (!authHeader.startsWith("Bearer ")){ // "Bearer <token>" 형식인지 확인
			return onError(exchange, "Bearer 형식이 아닙니다.", HttpStatus.UNAUTHORIZED, "INVALID_HEADER_FORMAT", "Authorization 헤더가 'Bearer '로 시작하지 않습니다.");
		}

		// JWT 검증
		String token = authHeader.replace("Bearer ", "").trim(); // "Bearer " 제거해서 실제 토큰 값만 추출
		if (!jwtUtil.isValidToken(token)) { // 만료 여부, 서명 위조 여부 검증
			return onError(exchange, "JWT 토큰이 유효하지 않습니다.", HttpStatus.UNAUTHORIZED, "INVALID_JWT", "토큰이 만료되었거나 변조되었습니다.");
		}
		Claims claims = jwtUtil.extractClaims(token); // 토큰 Payload에 담긴 Claims 꺼내기
		
		// 2. 토큰 추출 결과 로그
		log.info("FilterChain에서 토큰 추출 결과 - userId : {}", claims.get("user_id"));
		
		// 4. 인가 성공 시 라우팅 정보 로그 - 실제 라우팅 후 로그
		return chain.filter(exchange).doFinally(signalType -> {
			// 라우팅이 완료된 후 실제 타겟 URI 로그 출력
			java.net.URI targetUri = exchange.getAttribute(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
			if (targetUri != null) {
				log.info("인가 성공 - 실제 라우팅: {}://{}:{}{}", 
					targetUri.getScheme(), 
					targetUri.getHost(), 
					targetUri.getPort(), 
					targetUri.getPath());
			}
		});
	}

	// JWT 인증 없이 접근 가능한 엔드포인트 목록
	private static final List<String> PUBLIC_PATHS = List.of(
		// 회원가입, 로그인
		"/oauth2",
		"/oauth2/**",
		"/api/oauth2",
		"/api/oauth2/**",
        "/login",
        "/login/**",
		"/api/auth/signup", // 회원가입
		"/api/auth/reissue", // 토큰 재발급
		"/api/master/login", // 관리자 로그인
        "/api/auth/refresh",
		// 인증 보조 API
		"/api/auth/companies", // 회사 조회
		"/api/auth/departments", // 부서 조회
		"/api/auth/master/verify-code/sent", // 인증 코드 발송
		"/api/auth/master/verity-code/confirm", // 인증 코드 확인

		// 문서/개발 편의용
		"/api/auth/swagger-ui",
		"/api/auth/swagger-ui/**",
		"/api/auth/swagger-ui.html",
		"/api/auth/swagger-ui.css",
		"/api/auth/swagger-ui-bundle.js",
		"/api/auth/swagger-ui-standalone-preset.js",
		"/api/auth/swagger-initializer.js",
		"/api/auth/index.css",
		"/api/auth/favicon-32x32.png",
		"/api/auth/favicon-16x16.png",
		"/api/auth/v3/api-docs",
		"/api/auth/v3/api-docs/**",
		"/api/diaries/swagger-ui",
		"/api/diaries/swagger-ui/**",
		"/api/diaries/swagger-ui.html",
		"/api/diaries/v3/api-docs",
		"/api/courses/swagger-ui",
		"/api/courses/swagger-ui/**",
		"/api/courses/swagger-ui.html",
		"/api/courses/v3/api-docs",
		"/api/comments/swagger-ui",
		"/api/comments/v3/api-docs",
		"/swagger-ui/",
		"/swagger-ui/**",
		"/swagger-ui.html",
		"/swagger-resources/**",
		"/v3/api-docs",
		"/v3/api-docs/**",
		"/v3/api-docs.yaml",
		"/webjars",
		"/webjars/**",
		"/docs",

		// 외부 시스템 헬스체크 / 연동 API
		"/argocd",
		"/argocd/**",
		"/actuator/health",
		"/api/auth/health",
		"/api/courses/health",
		"/api/diaries/health",

		"/api/recommends",
		"/api/recommends/**"
	);

	// PUBLIC_PATHS에 포함되는지 확인
	private boolean isPublic(String path) {
		boolean isPublic = PUBLIC_PATHS.stream().anyMatch(publicPath -> {
			// /** 패턴 처리
			if (publicPath.endsWith("/**")) {
				String basePath = publicPath.substring(0, publicPath.length() - 3);
				boolean matches = path.startsWith(basePath);
				return matches;
			}
			// 일반 경로는 startsWith로 매칭
			boolean matches = path.startsWith(publicPath);
			return matches;
		});
		
		return isPublic;
	}

	// 에러 응답 생성
	private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status, String errorCode, String errorDetail) {
		// 3. 인가 실패 로그
		log.error("인가 실패: {} - {}", errorCode, errorDetail);

		var response = exchange.getResponse();
		response.setStatusCode(status);
		response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");

		String timestamp = java.time.OffsetDateTime.now().toString();

		String json  =String.format(
			"{" +
				"\"timestamp\": \"%s\"," + // 2025-09-20T16:45:00+09:00
				"\"success\": false," +
				"\"message\": \"%s\"," + // JWT 토큰이 유효하지 않습니다.
				"\"code\": %d," + // 401
				"\"error\": {\"code\": \"%s\", \"detail\": \"%s\"}" + // INVALID_JWT / 토큰이 만료되었거나 변조되었습니다.
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
	public int getOrder() { // CORS보다 먼저 실행
		return -1;
	}
}
