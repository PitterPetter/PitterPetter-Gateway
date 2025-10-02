package PitterPetter.loventure.gateway.filter;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Mono;

public class CorsErrorFilter implements GatewayFilter, Ordered {
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String origin = exchange.getRequest().getHeaders().getOrigin(); // 요청 헤더: http://localhost:5173
		if (origin != null && !isAllowedOrigin(origin)) { // 허용되지 않은 Origin일 때 onCorsError() 호출
			return onCorsError(exchange, origin);
		}
		return chain.filter(exchange); // 허용된 Origin이면 chain.filter(exchange) 호출 -> 다음 필터로
	}

	public boolean isAllowedOrigin(String origin) {
		// 허용할 Origin들을 리스트로 관리
		return List.of(
			"http://localhost:5173",
			"https://api.loventure.us",
			"https://loventure.us",
			"https://argo.loventure.us"
		).contains(origin);
	}

	public Mono<Void> onCorsError(ServerWebExchange exchange, String origin) {
		String timestamp = java.time.OffsetDateTime.now().toString();
		String json = String.format( // CORS 위반 시 반환할 JSON 응답 생성
			"{" +
				"\"timestamp\": \"%s\"," + // 2025-09-20T16:40:00+09:00
				"\"success\": false," +
				"\"message\": \"허용되지 않은 Origin: %s\"," + // http://evil.com
				"\"code\": %d," + // 403
				"\"error\": {\"code\": \"CORS_ORIGIN_DENIED\", \"detail\": \"Origin이 허용 목록에 없습니다.\"}" +
				"}",
			timestamp,
			origin,
			HttpStatus.FORBIDDEN.value()
		);

		var response = exchange.getResponse();
		response.setStatusCode(HttpStatus.FORBIDDEN); // HTTP 응답 상태 설정(403)
		response.getHeaders().add("Content-Type", "application/json"); // 응답 형식 지정(JSON)
		var buffer = response.bufferFactory().wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); // JSON 문자열을 응답 본문으로 변환
		return response.writeWith(Mono.just(buffer));
	}

	@Override
	public int getOrder() {
		return 0; // CORS 필터 가장 먼저 실행
	}
}
