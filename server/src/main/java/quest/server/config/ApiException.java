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
    /** 409: the request is fine but the row it would create is already there (a taken email address). */
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "conflict", message); }
    /** 409: the request names a day the school does not teach on — `SchoolCalendar` decides which those are. */
    public static ApiException notTeachingDay(String message) { return new ApiException(HttpStatus.CONFLICT, "not_teaching_day", message); }
    /**
     * 409 with one of §8's exam codes (`quest.api.dto.ApiError`): the request is well formed and the caller is
     * entitled to make it — the exam's own rules are what refuse it, and the app tells the child which rule it was.
     */
    public static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    /** 429 `rate_limited`: the caller is fine, the pace is not (C1: 30 chat messages a minute per sender). */
    public static ApiException rateLimited(String message) { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message); }
    public HttpStatus status() { return status; }
    public ApiError error() { return error; }
}
