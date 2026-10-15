package quest.server.ai;

import java.util.List;

/**
 * Why {@link AiService} has no answer, in the three shapes a caller needs to tell apart — which is the whole reason
 * the loop can be shared at all. The HTTP code stays with the caller: the lesson pipeline retries a
 * {@link Kind#TRANSIENT} failure and then says `model_unavailable`, the admin routes answer `502 model_failed`, and a
 * teacher's save answers `422 rephrase`.
 */
public final class AiFailure extends RuntimeException {
    /** {@code TRANSIENT}: the provider was busy or unreachable. {@code PROVIDER}: it answered, wrongly. {@code INVALID}: every attempt was rejected. */
    public enum Kind { TRANSIENT, PROVIDER, INVALID }

    private final Kind kind; private final List<String> errors; private final long inputTokens; private final long outputTokens;

    AiFailure(Kind kind, String message, Throwable cause, List<String> errors, long inputTokens, long outputTokens) {
        super(message, cause);
        this.kind = kind; this.errors = List.copyOf(errors); this.inputTokens = inputTokens; this.outputTokens = outputTokens;
    }

    public Kind kind() { return kind; }
    public boolean isTransient() { return kind == Kind.TRANSIENT; }
    /** The validator's own lines from the last attempt, in the words the caller puts in its message. */
    public List<String> errors() { return errors; }
    /** The first {@code max} errors, joined the way every call site joins them. */
    public String errorsJoined(int max) { return String.join("; ", errors.subList(0, Math.min(max, errors.size()))); }
    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }
    public long totalTokens() { return inputTokens + outputTokens; }
}
