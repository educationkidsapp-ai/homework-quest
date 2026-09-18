package quest.server.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Cached plays carry the model's short stop ids ("s1", "s9q2"); the same cached play may serve several lessons,
 * so stop ids are made lesson-unique when a play is attached to a lesson: {@code <lesson8>:<level>:<variant>:<id>}.
 */
public final class StopIds {
    private StopIds() {}

    public static String prefix(String lessonId, int level, int variant) { return prefix8(lessonId) + ":" + level + ":" + variant + ":"; }

    /** The lesson's share of every id it owns — stops, page images and skills all start with it. */
    public static String prefix8(String lessonId) { return lessonId.substring(0, Math.min(8, lessonId.length())); }

    /**
     * Moves every lesson-unique id in a subtree from one lesson to another: how a lesson is copied into a sibling
     * class (N2.1). Only the fields that <em>are</em> ids are touched — `id`, `stopId`, `pageImageId`, `skillId`,
     * `imageId` — and only when they already carry the source lesson's prefix, so prose that happens to look like an
     * id is left alone and an id that was never prefixed (a hand-written stop from another lesson) is not invented.
     *
     * <p>The play's own `"<lessonId>:<level>:<variant>"` is not of this shape and is rewritten by the caller.
     */
    public static void reprefix(JsonNode node, String fromLessonId, String toLessonId) {
        String from = prefix8(fromLessonId) + ":", to = prefix8(toLessonId) + ":";
        rewrite(node, from, to);
    }

    private static void rewrite(JsonNode node, String from, String to) {
        if (node instanceof ObjectNode o) {
            for (String field : new String[] {"id", "stopId", "pageImageId", "skillId", "imageId"})
                if (o.hasNonNull(field) && o.get(field).asText().startsWith(from)) o.put(field, to + o.get(field).asText().substring(from.length()));
            o.fields().forEachRemaining(e -> rewrite(e.getValue(), from, to));
        } else if (node instanceof ArrayNode a) for (JsonNode n : a) rewrite(n, from, to);
    }

    /** Rewrites every stop id (including exit-ticket questions) in a play JSON tree in place. */
    public static void relabel(ObjectNode play, String lessonId, int level, int variant) {
        String prefix = prefix(lessonId, level, variant);
        JsonNode stops = play.get("stops");
        if (stops instanceof ArrayNode arr) for (JsonNode s : arr) relabelStop((ObjectNode) s, prefix);
        play.put("id", lessonId + ":" + level + ":" + variant);
    }

    public static void relabelStop(ObjectNode stop, String prefix) {
        if (stop.hasNonNull("id") && !stop.get("id").asText().startsWith(prefix)) stop.put("id", prefix + stop.get("id").asText());
        JsonNode qs = stop.get("questions");
        if (qs instanceof ArrayNode arr) for (JsonNode q : arr) relabelStop((ObjectNode) q, prefix);
        relabelPageImages(stop, prefix.substring(0, prefix.indexOf(':') + 1));
    }

    /** `pageImageId: "page-3"` → `"<lesson8>:page-3"` anywhere in the subtree (tiles, cues, readPage). */
    static void relabelPageImages(JsonNode node, String lessonPrefix) {
        if (node instanceof ObjectNode o) {
            if (o.hasNonNull("pageImageId") && !o.get("pageImageId").asText().contains(":")) o.put("pageImageId", lessonPrefix + o.get("pageImageId").asText());
            if (o.hasNonNull("skillId") && !o.get("skillId").asText().contains(":")) o.put("skillId", lessonPrefix + o.get("skillId").asText());
            o.fields().forEachRemaining(e -> relabelPageImages(e.getValue(), lessonPrefix));
        } else if (node instanceof ArrayNode a) for (JsonNode n : a) relabelPageImages(n, lessonPrefix);
    }

    public static String pageImageId(String lessonId, int pageNumber) { return lessonId.substring(0, Math.min(8, lessonId.length())) + ":page-" + pageNumber; }

    /**
     * Parent-panel tips / model answers reference stops as "L<level>:<id>" (Prompt C); older answers may use the bare id or
     * "level1_s6". Each becomes the lesson-unique id; entries that match no stop are dropped rather than left dangling.
     */
    public static void relabelPanel(ObjectNode panel, String lessonId, java.util.Map<Integer, java.util.Set<String>> canonicalIdsByLevel) {
        for (String field : new String[] {"stopTips", "modelAnswers"}) {
            JsonNode arr = panel.get(field);
            if (!(arr instanceof ArrayNode a)) continue;
            ArrayNode kept = a.arrayNode();
            for (JsonNode n : a) {
                var o = (ObjectNode) n; String mapped = resolve(o.path("stopId").asText(), lessonId, canonicalIdsByLevel);
                if (mapped != null) { o.put("stopId", mapped); kept.add(o); }
            }
            panel.set(field, kept);
        }
    }

    static String resolve(String raw, String lessonId, java.util.Map<Integer, java.util.Set<String>> ids) {
        if (raw == null || raw.isBlank()) return null;
        var m = java.util.regex.Pattern.compile("^(?:L|level|l)?\\s*([123])\\s*[:_\\-]\\s*(.+)$").matcher(raw.trim());
        if (m.matches()) { int level = Integer.parseInt(m.group(1)); String id = m.group(2).trim(); if (ids.getOrDefault(level, java.util.Set.of()).contains(id)) return withPrefix(lessonId, level, id); }
        for (int level = 1; level <= 3; level++) if (ids.getOrDefault(level, java.util.Set.of()).contains(raw.trim())) return withPrefix(lessonId, level, raw.trim());
        return null;
    }

    /** Hand-written stops already carry the lesson prefix; model ids do not. */
    public static String withPrefix(String lessonId, int level, String id) {
        String p = prefix(lessonId, level, 0);
        return id.startsWith(p) ? id : p + id;
    }
}
