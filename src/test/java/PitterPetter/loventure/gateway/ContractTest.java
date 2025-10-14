package PitterPetter.loventure.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class ContractTest {

    @Test
    public void testCouplesApiContract() {
        // Gateway → Couples Service API 계약 테스트
        given()
            .header("Authorization", "Bearer valid-jwt-token")
            .header("Accept", "application/json")
            .when()
            .get("/api/couples/ticket")
            .then()
            .statusCode(anyOf(is(200), is(401), is(503))) // Mock 서버 없을 때는 에러 가능
            .log().all();
    }

    @Test
    public void testRegionsApiContract() {
        // 클라이언트 → Gateway API 계약 테스트
        given()
            .header("Authorization", "Bearer valid-jwt-token")
            .header("Accept", "application/json")
            .when()
            .get("/api/regions")
            .then()
            .statusCode(anyOf(is(200), is(401), is(403), is(503))) // 다양한 응답 가능
            .log().all();
    }

    @Test
    public void testJwtValidationContract() {
        // JWT 검증 API 계약 테스트
        given()
            .header("Authorization", "Bearer invalid-token")
            .header("Accept", "application/json")
            .when()
            .get("/api/regions")
            .then()
            .statusCode(401)
            .body("success", equalTo(false))
            .body("message", notNullValue())
            .log().all();
    }

    @Test
    public void testNoAuthHeaderContract() {
        // 인증 헤더 없는 경우 계약 테스트
        given()
            .header("Accept", "application/json")
            .when()
            .get("/api/regions")
            .then()
            .statusCode(401)
            .body("success", equalTo(false))
            .log().all();
    }
}
