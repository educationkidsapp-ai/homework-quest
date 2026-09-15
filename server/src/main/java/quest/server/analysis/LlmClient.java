package quest.server.analysis;

import java.util.List;

/** One JSON-returning completion. Providers: DeepSeek (default), Anthropic, or the sample (fake) client. */
public interface LlmClient {
    /** An image (`image/png`, `image/jpeg`) or a PDF (`application/pdf`) attached to the user turn. */
    record Attachment(String mimeType, byte[] bytes, String name) {}
    record Result(String text, long inputTokens, long outputTokens) { public long total() { return inputTokens + outputTokens; } }

    String name();
    /** Whether PDFs can be attached directly (otherwise pages are rendered to PNG first). */
    default boolean acceptsPdf() { return false; }
    Result complete(String system, String user, List<Attachment> attachments);

    /** {@code transientFailure}: the provider was busy/unreachable (429, 5xx, timeout) — worth retrying later; false = it answered but wrongly. */
    class LlmException extends RuntimeException {
        private final boolean transientFailure;
        public LlmException(String m, Throwable c) { this(m, c, false); }
        public LlmException(String m) { this(m, null, false); }
        public LlmException(String m, Throwable c, boolean transientFailure) { super(m, c); this.transientFailure = transientFailure; }
        public boolean isTransient() { return transientFailure; }
    }
}
