package quest.server.api;

import org.springframework.http.HttpStatus;
import quest.server.api.dto.ApiError;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ApiError error;

    public ApiException(HttpStatus status, String code, String message) {
        super(code + ": " + message);
        this.status = status;
        this.error = new ApiError(code, message);
    }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND, what + " not found"); }
    public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, ApiError.BAD_REQUEST, message); }

    public HttpStatus status() { return status; }
    public ApiError error() { return error; }
}
