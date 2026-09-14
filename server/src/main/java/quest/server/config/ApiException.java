package quest.server.config;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public record ApiError(String code, String message) {}
    private final HttpStatus status;
    private final ApiError error;

    public ApiException(HttpStatus status, String code, String message) { super(code + ": " + message); this.status = status; this.error = new ApiError(code, message); }
    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found"); }
    public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message); }
    public static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message); }
    public static ApiException unauthorized(String message) { return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message); }
    public HttpStatus status() { return status; }
    public ApiError error() { return error; }
}
