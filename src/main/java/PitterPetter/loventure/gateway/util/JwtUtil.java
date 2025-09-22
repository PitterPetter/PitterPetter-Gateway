package PitterPetter.loventure.gateway.util;

import java.security.Key;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

//@Component
public class JwtUtil {
	private final Key key;

	public JwtUtil(@Value("${jwt.secret}") String secret) { // JWT 검증에 쓸 Key를 클래스 내부에 준비
		byte[] decodedKey = java.util.Base64.getDecoder().decode(secret); // Base64 인코딩된 값 -> 바이트 배열로 디코딩
		this.key = Keys.hmacShaKeyFor(decodedKey); // HMAC-SHA 알고리즘용 Key 객체 생성
	}

	public boolean isValidToken (String token) { // 토큰 유효성 검사
		try {
			Jwts.parserBuilder()
				.setSigningKey(key)
				.build()
				.parseClaimsJws(token); // 토큰 실제로 파싱 & 검증 -> 예외 발생하지 않으면 유효 -> ture
			return true;
		} catch (JwtException | IllegalArgumentException e) {
			return false;
		}
	}

	public Claims extractClaims(String token) { // Claims 추출
		return Jwts.parserBuilder()
			.setSigningKey(key)
			.build()
			.parseClaimsJws(token)
			.getBody(); // 토큰 안에 있는 Claims(payload 부분) 꺼냄
	}
}
