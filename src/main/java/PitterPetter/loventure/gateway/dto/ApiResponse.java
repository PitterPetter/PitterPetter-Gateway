package PitterPetter.loventure.gateway.dto;

public record ApiResponse<T>(
    String status,
    String message,
    T data
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("success", "Success", data);
    }
    
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>("success", message, data);
    }
    
    public static <T> ApiResponse<T> error(String status, String message) {
        return new ApiResponse<>(status, message, null);
    }
    
    public static <T> ApiResponse<T> error(String status, String message, T data) {
        return new ApiResponse<>(status, message, data);
    }
}



