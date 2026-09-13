package quest.server.api.dto;

public record ApiError(String code, String message) {
    public static final String UNREADABLE_FILE = "unreadable_file";
    public static final String NO_TEACHING_CONTENT = "no_teaching_content";
    public static final String MODEL_FAILED = "model_failed";
    public static final String TOO_LARGE = "too_large";
    public static final String NOT_FOUND = "not_found";
    public static final String BAD_REQUEST = "bad_request";
}
