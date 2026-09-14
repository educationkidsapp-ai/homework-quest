package quest.server.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import quest.api.Illustrations;
import quest.api.dto.Play;
import quest.api.validation.SchemaValidator;
import quest.api.validation.ValidationResult;

/** Small repairs on model output that need no second call, and readable validation errors for the retry turn. */
final class LlmJson {
    private LlmJson() {}
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KNOWN = new HashSet<>(Illustrations.INSTANCE.getKeys());
    private static final Set<String> PIECES = Set.of("title", "genre", "characters", "setting", "plot", "problem");

    /** Unknown illustration keys are dropped where the key is optional (tiles, cues, order items, readPage, page lists); enum-like fields are lower-cased. */
    static String cleanIllustrations(String raw) {
        try { JsonNode node = MAPPER.readTree(raw); clean(node); return node.toString(); } catch (IOException e) { return raw; }
    }

    private static void clean(JsonNode node) {
        if (node instanceof ObjectNode o) {
            if (o.hasNonNull("illustrationKey") && !KNOWN.contains(o.get("illustrationKey").asText())) {
                boolean optional = o.has("label") || o.has("cue") || o.has("text") || o.has("sentences") || o.has("pageImageId");
                if (optional) o.remove("illustrationKey");
            }
            for (String enumField : new String[] {"piece", "stage", "mode", "genre", "kind", "subject"})
                if (o.get(enumField) != null && o.get(enumField).isTextual()) o.put(enumField, o.get(enumField).asText().trim().toLowerCase());
            if (o.get("cards") instanceof ArrayNode cards && cards.size() > 6) {   // storyPieces: drop cards outside the six pieces (e.g. "resolution")
                ArrayNode kept = MAPPER.createArrayNode(); for (JsonNode c : cards) if (PIECES.contains(c.path("piece").asText().toLowerCase())) kept.add(c);
                o.set("cards", kept);
            }
            if (o.get("illustrationKeys") instanceof ArrayNode keys) {
                ArrayNode kept = MAPPER.createArrayNode(); for (JsonNode k : keys) if (KNOWN.contains(k.asText())) kept.add(k);
                o.set("illustrationKeys", kept);
            }
            o.fields().forEachRemaining(e -> clean(e.getValue()));
        } else if (node instanceof ArrayNode a) for (JsonNode n : a) clean(n);
    }

    /** Schema errors from a `oneOf` are noisy; when the play decodes, the shared semantic rules explain the problem in one line each. */
    static ValidationResult validatePlay(String raw, int level, Set<String> excludedIds) {
        ValidationResult r = SchemaValidator.INSTANCE.validatePlayJson(raw, level, excludedIds);
        if (r.getErrors().isEmpty()) return r;
        try {
            Play play = SchemaValidator.INSTANCE.getJson().decodeFromString(Play.Companion.serializer(), raw);
            List<String> semantic = SchemaValidator.INSTANCE.validate(play, level, excludedIds).getErrors();
            if (!semantic.isEmpty()) { List<String> all = new ArrayList<>(semantic); all.addAll(r.getErrors().subList(0, Math.min(3, r.getErrors().size()))); return new ValidationResult(all); }
        } catch (Exception ignored) { /* the schema errors are the best we have */ }
        return r;
    }
}
