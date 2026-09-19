package quest.server.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quest.api.Illustrations;
import quest.api.dto.Play;
import quest.api.validation.Schemas;
import quest.api.validation.SchemaValidator;
import quest.api.validation.ValidationResult;

/** Small repairs on model output that need no second call, and readable validation errors for the retry turn. */
final class LlmJson {
    private LlmJson() {}
    private static final Logger log = LoggerFactory.getLogger(LlmJson.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KNOWN = new HashSet<>(Illustrations.INSTANCE.getKeys());
    private static final Set<String> PIECES = Set.of("title", "genre", "characters", "setting", "plot", "problem");
    private static final Set<String> SINGLE_TYPES = Set.of("choice", "trueFalse", "sequence", "count", "compare", "sound", "word", "readTap");
    /** Child-facing string limits from Play.schema.json; a few characters over is trimmed at a word boundary instead of costing a retry. */
    private static final java.util.Map<String, Integer> LIMITS = java.util.Map.ofEntries(
            java.util.Map.entry("title", 40), java.util.Map.entry("speak", 90), java.util.Map.entry("hint", 90), java.util.Map.entry("question", 90), java.util.Map.entry("statement", 90),
            java.util.Map.entry("prompt", 90), java.util.Map.entry("explanation", 80), java.util.Map.entry("cue", 90), java.util.Map.entry("meaning", 80), java.util.Map.entry("sentence", 120),
            java.util.Map.entry("definition", 90), java.util.Map.entry("frame", 90), java.util.Map.entry("servedText", 60), java.util.Map.entry("dishName", 30), java.util.Map.entry("potName", 20),
            java.util.Map.entry("en", 200), java.util.Map.entry("ar", 200), java.util.Map.entry("modelAnswer", 300), java.util.Map.entry("pictureDescription", 120), java.util.Map.entry("text", 80),
            // Prompt A (SourceAnalysis): skills, story pieces, facts
            java.util.Map.entry("method", 120), java.util.Map.entry("name", 40), java.util.Map.entry("setting", 90), java.util.Map.entry("problem", 120), java.util.Map.entry("resolution", 120),
            java.util.Map.entry("trueStatement", 90), java.util.Map.entry("falseTwin", 90), java.util.Map.entry("word", 16));
    /** Arrays of strings with a per-item limit (page sentences, examples, objectives…). */
    private static final java.util.Map<String, Integer> ARRAY_LIMITS = java.util.Map.of(
            "childText", 90, "examples", 120, "candidates", 40, "characters", 30, "sentences", 90, "steps", 80, "options", 40);

    /**
     * The property names each stop type declares, read once from the shared `Play.schema.json` (every `stop_<type>`
     * branch is `additionalProperties: false`), so this can never drift from the contract.
     */
    private static final Map<String, Set<String>> STOP_FIELDS = stopFields();

    private static Map<String, Set<String>> stopFields() {
        Map<String, Set<String>> byType = new HashMap<>();
        try {
            JsonNode defs = MAPPER.readTree(Schemas.INSTANCE.getPlay()).path("$defs");
            defs.forEach(def -> {
                JsonNode type = def.path("properties").path("type").path("const");
                if (!type.isTextual() || def.path("additionalProperties").asBoolean(true)) return;
                Set<String> names = new HashSet<>(); def.path("properties").fieldNames().forEachRemaining(names::add);
                byType.put(type.asText(), names);
            });
        } catch (IOException e) { log.warn("could not read the stop fields from Play.schema.json: {}", e.toString()); }
        return Map.copyOf(byType);
    }

    /**
     * Stray properties the model adds to a stop — most often a `hint` on one of the 13 types whose schema branch has
     * no `hint` — cost a whole retry turn for nothing, so they are dropped here: for every object that declares a
     * known stop `type` (including the three inside an exitTicket), whatever that type's branch does not declare goes.
     */
    static String dropUnknownStopFields(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            List<String> dropped = new ArrayList<>();
            dropUnknown(node, dropped);
            if (dropped.isEmpty()) return raw;
            log.debug("dropped {} stop propert{} the play schema forbids: {}", dropped.size(), dropped.size() == 1 ? "y" : "ies", dropped);
            return node.toString();
        } catch (IOException e) { return raw; }
    }

    private static void dropUnknown(JsonNode node, List<String> dropped) {
        if (node instanceof ObjectNode o) {
            Set<String> allowed = STOP_FIELDS.get(o.path("type").asText());
            if (allowed != null) {
                List<String> extra = new ArrayList<>(); o.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f)) extra.add(f); });
                for (String f : extra) { dropped.add(o.path("type").asText() + " " + o.path("id").asText("?") + ": " + f); o.remove(f); }
            }
            o.fields().forEachRemaining(e -> dropUnknown(e.getValue(), dropped));
        } else if (node instanceof ArrayNode a) for (JsonNode n : a) dropUnknown(n, dropped);
    }

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
            if (SINGLE_TYPES.contains(o.path("type").asText()) && !o.hasNonNull("hint")) o.put("hint", "Look again and try once more.");
            // wordCards / readTap / sound need a picture key per entry: the word itself when we can draw it, else a neutral card
            if (o.has("word") && !o.hasNonNull("illustrationKey") && (o.has("meaning") || o.has("sentence"))) o.put("illustrationKey", KNOWN.contains(o.path("word").asText().toLowerCase()) ? o.path("word").asText().toLowerCase() : "book");
            if ("wordCards".equals(o.path("type").asText()) && o.get("words") instanceof ArrayNode words) {   // drop non-object entries the model sometimes emits (plain strings)
                ArrayNode kept = MAPPER.createArrayNode(); for (JsonNode w : words) if (w.isObject()) kept.add(w);
                if (kept.size() != words.size()) o.set("words", kept);
            }
            LIMITS.forEach((field, max) -> { JsonNode v = o.get(field); if (v != null && v.isTextual() && v.asText().length() > max) o.put(field, trim(v.asText(), max)); });
            if (o.has("emoji") && o.get("name") != null && o.get("name").isTextual() && o.get("name").asText().length() > 20) o.put("name", trim(o.get("name").asText(), 20)); // ingredient names are shorter
            ARRAY_LIMITS.forEach((field, max) -> { if (o.get(field) instanceof ArrayNode a) for (int i = 0; i < a.size(); i++) if (a.get(i).isTextual() && a.get(i).asText().length() > max) a.set(i, MAPPER.getNodeFactory().textNode(trim(a.get(i).asText(), max))); });
            if (o.has("objectives") && o.get("objectives") instanceof ObjectNode obj) for (String lang : new String[] {"en", "ar"})   // parent-facing objective lines (160)
                if (obj.get(lang) instanceof ArrayNode a) for (int i = 0; i < a.size(); i++) if (a.get(i).isTextual() && a.get(i).asText().length() > 160) a.set(i, MAPPER.getNodeFactory().textNode(trim(a.get(i).asText(), 160)));
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

    static String trim(String s, int max) {
        String t = s.substring(0, max - 1); int cut = t.lastIndexOf(' ');
        return (cut > max / 2 ? t.substring(0, cut) : t).replaceAll("[,;:\\s]+$", "") + "…";
    }

    /**
     * CR5: what is wrong with <em>this one stop</em>, having dropped it into the play it belongs to.
     *
     * <p>The stop is validated in place rather than alone, because the schema reaches it through the play's `oneOf`
     * and that is what picks the right `stop_&lt;type&gt;` branch. What comes back is filtered to the stop the
     * teacher edited: a play that was already short of its six stops, or whose neighbour the model never saw, is not
     * her problem, and telling her about it would make "save what I wrote" fail for a reason no rephrasing fixes.
     * On her own stop it is exactly as strict as the raw-JSON `PUT` — the same JSON-Schema branch, then the same
     * shared semantic rules, lenient for a hand-written lesson in the same way.
     */
    static List<String> stopErrors(String trialJson, int index, String stopId, int level, boolean lenient) {
        String at = "/stops/" + index;
        List<String> schema = SchemaValidator.INSTANCE.validatePlayJson(trialJson, level, Set.of()).getErrors().stream()
                .filter(e -> e.startsWith(at + "/") || e.startsWith(at + ":")).toList();
        if (!schema.isEmpty()) return schema;
        try {
            Play play = SchemaValidator.INSTANCE.getJson().decodeFromString(Play.Companion.serializer(), trialJson);
            return SchemaValidator.INSTANCE.validate(play, level, Set.of(), lenient).getErrors().stream()
                    .filter(e -> e.startsWith("stop " + stopId + ":")).toList();
        } catch (Exception e) { return List.of(); }   // the play as a whole is not what this save is being judged on
    }

    /** Schema errors from a `oneOf` are noisy; when the play decodes, the shared semantic rules explain the problem in one line each. */
    static ValidationResult validatePlay(String raw, int level, Set<String> excludedIds) {
        ValidationResult r = SchemaValidator.INSTANCE.validatePlayJson(raw, level, excludedIds);
        if (r.getErrors().isEmpty()) return r;
        try {
            Play play = SchemaValidator.INSTANCE.getJson().decodeFromString(Play.Companion.serializer(), raw);
            List<String> semantic = SchemaValidator.INSTANCE.validate(play, level, excludedIds, false).getErrors();
            if (!semantic.isEmpty()) { List<String> all = new ArrayList<>(semantic); all.addAll(r.getErrors().subList(0, Math.min(3, r.getErrors().size()))); return new ValidationResult(all); }
        } catch (Exception ignored) { /* the schema errors are the best we have */ }
        return r;
    }
}
