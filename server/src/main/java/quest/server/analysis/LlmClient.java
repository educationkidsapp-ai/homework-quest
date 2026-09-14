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

    class LlmException extends RuntimeException { public LlmException(String m, Throwable c) { super(m, c); } public LlmException(String m) { super(m); } }
}
