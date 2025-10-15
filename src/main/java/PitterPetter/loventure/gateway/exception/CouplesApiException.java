package PitterPetter.loventure.gateway.exception;

import org.springframework.http.HttpStatus;

public class CouplesApiException extends RuntimeException {
    private final HttpStatus statusCode;
    
    public CouplesApiException(String message, HttpStatus statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
    
    public CouplesApiException(String message, HttpStatus statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
    
    public HttpStatus getStatusCode() {
        return statusCode;
    }
}
