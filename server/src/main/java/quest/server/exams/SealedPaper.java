package quest.server.exams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import quest.server.config.Json;
import quest.server.grading.PaperSeal;

/**
 * B3 (D2): an exam paper as a child's device receives it before the results are released — the shape every installed
 * app decodes and plays, and nothing in it that is the answer (`Stop` in shared-api documents the same list).
 *
 * <ul>
 *   <li>every option, tile, item, pair and hotspot id becomes {@link PaperSeal#opaque} and the lists are put in the
 *       order of those ids, so neither an id nor a position says anything;</li>
 *   <li>every key field keeps its shape with a meaningless value: `correctOptionId` and `correctIds` are the first
 *       options (or hotspot) sent — as many as a multi-select's `pick`, `trueFalse.answer` is `false`, `correctOrder` is the items in the order sent (an app
 *       sizes the order stop's slots by it), a word-tile sentence's `answer` is its first word;</li>
 *   <li>a match stop's right-hand tiles are given to the pairs by {@link PaperSeal#rightOwners}, not by the pairing;</li>
 *   <li>`hint`, `modelAnswer` and `parentTip` say {@link #BLANK} (the schema wants them non-empty);
 *       `numberLine.highlight`, `teacherText`, and the panel's stop tips and model answers are emptied; each play
 *       says `"sealed": true`.</li>
 * </ul>
 *
 * <p>It works on the encoded JSON rather than on the 22 stop classes, so a field is handled wherever it appears and
 * an exit ticket's questions get the same treatment.
 */
@Component
public class SealedPaper {
    /** What a text the schema requires non-empty says on a sealed paper: nothing. */
    static final String BLANK = "…";

    private final Json json;

    public SealedPaper(Json json) { this.json = json; }

    public String seal(String publishedJson, PaperSeal seal) {
        var root = json.tree(publishedJson);
        for (var play : root.path("plays")) play(play, seal);
        play(root.path("variant"), seal);
        play(root.path("examPlay"), seal);
        if (root.get("parentPanel") instanceof ObjectNode panel) { panel.putArray("stopTips"); panel.putArray("modelAnswers"); }
        return json.write(root);
    }

    private void play(JsonNode play, PaperSeal seal) {
        if (!(play instanceof ObjectNode p)) return;
        p.put("sealed", true);
        for (var stop : p.path("stops")) if (stop instanceof ObjectNode s) seal(s, seal);
    }

    void seal(ObjectNode s, PaperSeal seal) {
        String id = s.path("id").asText();
        s.remove("teacherText");
        if (s.has("hint")) s.put("hint", BLANK);
        if (s.has("parentTip")) s.putObject("parentTip").put("en", BLANK).put("ar", BLANK);
        if (s.has("modelAnswer")) s.put("modelAnswer", BLANK);
        if (s.get("numberLine") instanceof ObjectNode line) line.putArray("highlight");
        if (s.get("options") instanceof ArrayNode options) {
            var sent = options.size() > 0 && options.get(0).isTextual() ? words(options, id, seal) : objects(options, id, seal);
            s.set("options", sent);
            if (s.has("correctOptionId")) s.put("correctOptionId", sent.isEmpty() ? "" : sent.get(0).path("id").asText());
        }
        if (s.has("correctIds")) placeholder(s, s.path("options"), Math.max(1, s.path("pick").asInt(1)));
        if (s.get("tapTask") instanceof ObjectNode task) {
            for (var hotspot : task.path("hotspots")) if (hotspot instanceof ObjectNode h) h.put("id", seal.opaque(id, h.path("id").asText()));
            placeholder(task, task.path("hotspots"), 1);
        }
        switch (s.path("type").asText()) {
            case "trueFalse" -> s.put("answer", false);
            case "writeSentence" -> {
                if (s.get("options") instanceof ArrayNode words && !words.isEmpty() && !s.path("free").asBoolean(false)) s.put("answer", words.get(0).asText());
            }
            case "order" -> {
                var items = objects((ArrayNode) s.path("items"), id, seal);
                s.set("items", items);
                var order = s.putArray("correctOrder");
                for (var item : items) order.add(item.path("id").asText());
            }
            case "match" -> {
                var byId = new LinkedHashMap<String, JsonNode>();
                for (var pair : s.path("pairs")) byId.put(pair.path("id").asText(), pair);
                var ids = new ArrayList<>(byId.keySet());
                var owners = seal.rightOwners(id, ids);
                var pairs = s.putArray("pairs");
                for (var pairId : seal.order(id, ids)) {
                    var out = pairs.addObject();
                    out.put("id", seal.opaque(id, pairId));
                    out.set("left", tile(byId.get(pairId).get("left"), id, seal));
                    out.set("right", tile(byId.get(owners.get(pairId)).get("right"), id, seal));
                }
            }
            case "exitTicket" -> { for (var q : s.path("questions")) if (q instanceof ObjectNode question) seal(question, seal); }
            default -> { }
        }
    }

    /**
     * `correctIds` as a placeholder the schema accepts: the first `count` ids sent — a multi-select's `pick` of them,
     * a number the stop already shows — which are not the key.
     */
    private static void placeholder(ObjectNode owner, JsonNode sent, int count) {
        var ids = owner.putArray("correctIds");
        for (int i = 0; i < Math.min(count, sent.size()); i++) ids.add(sent.get(i).path("id").asText());
    }

    /** Objects with an `id` (tiles, options, pictures, items): opaque ids, in the order of those ids. */
    private ArrayNode objects(ArrayNode in, String stopId, PaperSeal seal) {
        var byId = new LinkedHashMap<String, JsonNode>();
        for (var node : in) byId.put(node.path("id").asText(), node);
        var out = json.array();
        for (var original : seal.order(stopId, new ArrayList<>(byId.keySet()))) {
            var copy = (ObjectNode) byId.get(original).deepCopy();
            copy.put("id", seal.opaque(stopId, original));
            out.add(copy);
        }
        return out;
    }

    /** A word-tile sentence's words are their own ids (the player answers with the word), so only their order changes. */
    private ArrayNode words(ArrayNode in, String stopId, PaperSeal seal) {
        List<String> words = new ArrayList<>();
        for (var w : in) words.add(w.asText());
        var out = json.array();
        for (var w : seal.order(stopId, words)) out.add(w);
        return out;
    }

    private static JsonNode tile(JsonNode tile, String stopId, PaperSeal seal) {
        var copy = (ObjectNode) tile.deepCopy();
        copy.put("id", seal.opaque(stopId, "tile|" + tile.path("id").asText()));
        return copy;
    }
}
