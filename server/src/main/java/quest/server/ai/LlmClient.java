package quest.server.ai;

import java.util.List;

/** The only thing the pipeline needs from an LLM. Mocked in tests. */
public interface LlmClient {
    sealed interface Block permits Block.Text, Block.Pdf, Block.Image {
        record Text(String text) implements Block {}
        record Pdf(byte[] bytes, String title) implements Block {}
        record Image(byte[] bytes, String mediaType) implements Block {}
    }

    record Turn(String role, List<Block> blocks) {}

    /** Sends one conversation and returns the assistant text (JSON expected). */
    String complete(String system, List<Turn> turns) throws LlmException;

    /** Whether {@link Block.Pdf} may be sent directly; otherwise the pipeline renders PDF pages to images. */
    default boolean supportsPdf() { return true; }

    class LlmException extends Exception {
        public LlmException(String message, Throwable cause) { super(message, cause); }
        public LlmException(String message) { super(message); }
    }
}
