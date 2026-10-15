package quest.server.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An answer {@link AiService} accepted: the reader's own normalised text, its parsed JSON when the reader parsed it
 * (null when it only ever held text), and what every attempt cost — the tokens of the rejected attempts included,
 * because the provider billed for those too.
 */
public record AiOutcome(String text, JsonNode json, long inputTokens, long outputTokens, int attempts) {
    public long totalTokens() { return inputTokens + outputTokens; }
}
