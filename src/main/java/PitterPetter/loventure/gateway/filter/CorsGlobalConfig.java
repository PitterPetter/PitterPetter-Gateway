package PitterPetter.loventure.gateway.filter;

import java.util.List;

import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;

@Configurable
public class CorsGlobalConfig {
	@Bean
	public CorsWebFilter corsWebFilter() {
		// CORS 설정 객체 생성
		CorsConfiguration config = new CorsConfiguration();

		// 허용할 HTTP 메서드
		config.setAllowedOrigins(List.of(
			"http://localhost:3000/api", "http://loventure.us" //, 쿠버 서버 추가하기
		));
		config.setAllowedMethods(List.of("*")); // 허용할 메쏘드
		config.setAllowedHeaders(List.of("*")); // 허용할 요청 헤더
		config.setAllowCredentials(true); // Credential 허용

		// 매핑할 URL 패턴
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);

		// 필터 등록
		return new CorsWebFilter(source);
	}
}
