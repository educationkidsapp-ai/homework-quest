package quest.server.exams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import quest.server.config.Json;
import quest.server.grading.AnswerKey;

/**
 * B3 (D2): an exam paper as a child's device receives it before the results are released — with no answer key.
 * Every stop of `plays`, `variant` and `examPlay` (an exit ticket's questions included) loses what would let a
 * downloaded paper be answered perfectly: `correctOptionId`, `trueFalse.answer`, `correctIds`, `correctOrder`
 * (and the items' authored order), the pairing of a match stop, the hint, the model answer, the word of a
 * word-tile sentence, the number line's highlight, the parent tip and the panel's stop tips and model answers.
 * `Stop` in shared-api documents the same list for the player.
 *
 * <p>It works on the encoded JSON rather than on the 22 stop classes, so a field is redacted wherever it appears,
 * and the shapes the app decodes are unchanged. The shuffles are {@link AnswerKey#shuffled} keyed by a server-only
 * salt, which is what lets {@link AnswerKey} grade a match answer against the tiles the child was actually shown.
 */
@Component
public class SealedPaper {
    private final Json json;

    public SealedPaper(Json json) { this.json = json; }

    public String seal(String publishedJson, String salt) {
        var root = json.tree(publishedJson);
        for (var play : root.path("plays")) stops(play, salt);
        stops(root.path("variant"), salt);
        stops(root.path("examPlay"), salt);
        if (root.get("parentPanel") instanceof ObjectNode panel) { panel.putArray("stopTips"); panel.putArray("modelAnswers"); }
        return json.write(root);
    }

    private void stops(JsonNode play, String salt) { for (var stop : play.path("stops")) if (stop instanceof ObjectNode s) seal(s, salt); }

    void seal(ObjectNode s, String salt) {
        String id = s.path("id").asText();
        s.remove("teacherText");
        if (s.has("hint")) s.put("hint", "");
        if (s.has("parentTip")) s.putObject("parentTip").put("en", "").put("ar", "");
        if (s.has("correctOptionId")) s.put("correctOptionId", "");
        if (s.has("correctIds")) s.putArray("correctIds");
        if (s.has("modelAnswer")) s.put("modelAnswer", "");
        if (s.get("numberLine") instanceof ObjectNode line) line.putArray("highlight");
        if (s.get("tapTask") instanceof ObjectNode task) task.putArray("correctIds");
        switch (s.path("type").asText()) {
            case "trueFalse" -> s.remove("answer");
            case "writeSentence" -> { if (s.hasNonNull("options") && !s.path("free").asBoolean(false)) s.put("answer", ""); }
            case "order" -> {
                var byId = byId(s.path("items"));
                var items = s.putArray("items");
                for (var itemId : AnswerKey.shuffled(salt, id, new ArrayList<>(byId.keySet()))) items.add(byId.get(itemId));
                s.putArray("correctOrder");
            }
            case "match" -> {
                var byId = byId(s.path("pairs"));
                var ids = new ArrayList<>(byId.keySet());
                var shown = AnswerKey.shuffled(salt, id, ids);
                var rights = new LinkedHashMap<String, JsonNode>();
                for (var pairId : ids) rights.put(pairId, byId.get(pairId).get("right").deepCopy());
                for (int i = 0; i < ids.size(); i++) ((ObjectNode) byId.get(ids.get(i))).set("right", rights.get(shown.get(i)));
            }
            case "exitTicket" -> { for (var q : s.path("questions")) if (q instanceof ObjectNode question) seal(question, salt); }
            default -> { }
        }
    }

    private static LinkedHashMap<String, JsonNode> byId(JsonNode array) {
        var out = new LinkedHashMap<String, JsonNode>();
        for (var node : array) out.put(node.path("id").asText(), node);
        return out;
    }
}
