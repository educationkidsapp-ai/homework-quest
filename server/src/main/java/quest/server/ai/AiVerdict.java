package quest.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * What one raw model answer turned out to be: the value to keep, or the errors the next attempt is told about.
 *
 * <p>The repairs and the validation themselves stay with the caller ({@link AiTask.Reader}), because every call site
 * does something slightly different with the text before it judges it — Prompt A cleans illustration keys, Prompt B
 * also drops stop properties the schema forbids, the stop rewriter relabels ids and drops the stop into the real play
 * first, and feature 22 checks three fields and a date. {@link AiService} only needs to know whether to go round
 * again and, if so, with what.
 */
public record AiVerdict(String text, JsonNode json, List<String> errors) {
    /** Accepted, as text — the caller's own normalised string, which is what goes into its cache. */
    public static AiVerdict ok(String text) { return new AiVerdict(text, null, List.of()); }
    /** Accepted, as JSON the reader has already parsed and repaired; {@link AiOutcome#text()} is its serialisation. */
    public static AiVerdict ok(JsonNode json) { return new AiVerdict(json.toString(), json, List.of()); }
    /** Rejected, in the words the next attempt is given. Never empty: "no errors" is {@link #ok}. */
    public static AiVerdict rejected(List<String> errors) {
        if (errors == null || errors.isEmpty()) throw new IllegalArgumentException("a rejected answer needs at least one error");
        return new AiVerdict(null, null, List.copyOf(errors));
    }
    public static AiVerdict rejected(String error) { return rejected(List.of(error)); }

    public boolean accepted() { return errors.isEmpty(); }
}
