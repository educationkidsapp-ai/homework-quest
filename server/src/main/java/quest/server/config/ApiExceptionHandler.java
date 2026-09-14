package quest.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import quest.server.config.ApiException.ApiError;

/** Every error leaves as `{code, message}` (the app decodes it). Bodies never contain slide content. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class) public ResponseEntity<ApiError> api(ApiException e) { return ResponseEntity.status(e.status()).body(e.error()); }
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<ApiError> denied(AccessDeniedException e) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("forbidden", "Not allowed.")); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse("invalid request");
        return ResponseEntity.badRequest().body(new ApiError("bad_request", msg));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) { return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiError("too_large", "Those files are too big.")); }
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiError> noRoute(Exception e) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("not_found", "No such endpoint.")); }
    @ExceptionHandler({org.springframework.web.HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiError> method(Exception e) { return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ApiError("bad_request", "Method not allowed.")); }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class, org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> missing(Exception e) { return ResponseEntity.badRequest().body(new ApiError("bad_request", e.getMessage() == null ? "Bad request." : e.getMessage().split("\\n")[0])); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegal(IllegalArgumentException e) { return ResponseEntity.badRequest().body(new ApiError("bad_request", e.getMessage())); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) { log.error("unhandled {}", e.getClass().getSimpleName(), e); return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("internal", "Something went wrong on the server.")); }
}
