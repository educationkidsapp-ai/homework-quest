package quest.server.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.Play;
import quest.api.dto.Stop;
import quest.api.dto.StopKt;
import quest.api.validation.Schemas;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.StopEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.content.StopRepository;

/**
 * CR5, the half a teacher cannot see: her prose back into stop JSON (Prompt D).
 *
 * <p>The other direction is `StopText.describe`, which is code. This one is a model call because it has to
 * understand what she changed, and it is the only model call CR5 adds. Three things make it safe to run on a save
 * button: the system turn inlines the one `$defs.stop_&lt;type&gt;` branch her stop belongs to (so the model is
 * given the contract, not asked to remember it), the user turn carries the stop as it stands (so a sentence she
 * rewrote cannot quietly drop the eleven fields she never read), and the answer is validated in place — dropped
 * into the real play and run through the same validator the pipeline uses — before anything is stored.
 *
 * <p>It retries exactly once, with the validator's own errors appended, and then gives up with
 * {@code 422 rephrase} rather than a fifth attempt: at that point the text is asking for something the schema
 * cannot hold, and the honest answer is the owner's — "Couldn't save, please rephrase" — with the errors carried in
 * the message for the raw-JSON panel an admin can open.
 */
@Service
public class StopTextService {
    private static final Logger log = LoggerFactory.getLogger(StopTextService.class);
    /** `{ "code": "rephrase" }` — the save could not be made to match the schema, twice. */
    public static final String REPHRASE = "rephrase";
    private static final Pattern REF = Pattern.compile("\"\\$ref\"\\s*:\\s*\"#/\\$defs/([^\"]+)\"");
    private static final int MAX_TEXT = 8000;

    private final StopRepository stops; private final PlayRepository plays; private final LessonRepository lessons;
    private final LessonStore store; private final LlmClient llm; private final Json json; private final LessonState state; private final quest.server.tenancy.TenantGuard guard;

    public StopTextService(StopRepository stops, PlayRepository plays, LessonRepository lessons, LessonStore store, LlmClient llm, Json json, LessonState state, quest.server.tenancy.TenantGuard guard) {
        this.stops = stops; this.plays = plays; this.lessons = lessons; this.store = store; this.llm = llm; this.json = json; this.state = state; this.guard = guard;
    }

    /**
     * Saves one stop from the teacher's text: convert, validate, store the JSON <em>and</em> the text, and hand back
     * the stop carrying the text she wrote (so re-opening the editor never re-converts).
     */
    @Transactional
    public Stop fromText(String stopId, String text) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("Write what this stop should do first.");
        if (text.length() > MAX_TEXT) throw ApiException.badRequest("That is longer than one stop can hold.");
        guard.requireLessonWrite();   // the same gate the raw-JSON save goes through: Managerial reads lessons, never writes them
        StopEntity se = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("stop"));
        var lesson = lessons.findById(se.getLessonId()).orElseThrow(() -> ApiException.notFound("lesson"));
        var status = LessonState.status(lesson);
        if (status == quest.api.dto.LessonStatus.ANALYZING || status == quest.api.dto.LessonStatus.GENERATING || status == quest.api.dto.LessonStatus.UPLOADING)
            throw ApiException.badRequest("Wait for this lesson to finish generating before editing a stop.");
        var pe = plays.findById(se.getPlayId()).orElseThrow(() -> ApiException.notFound("play"));

        ObjectNode playNode = (ObjectNode) json.tree(pe.getPlayJson());
        ArrayNode playStops = (ArrayNode) playNode.get("stops");
        int index = -1; for (int i = 0; i < playStops.size(); i++) if (stopId.equals(playStops.get(i).path("id").asText())) index = i;
        if (index < 0) throw ApiException.notFound("stop");

        String system = Prompts.SYSTEM_D.formatted(branchSchema(se.getType()));
        String user = Prompts.userD(se.getType(), stopId, se.getContentJson(), text);
        long usage = 0; List<String> errors = List.of(); ObjectNode accepted = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String u = attempt == 0 ? user : user + "\n\nYour previous answer was rejected by the validator:\n- " + String.join("\n- ", errors) + "\nAnswer again with corrected JSON only.";
            LlmClient.Result r;
            // The provider billed for the first attempt whether or not the second one answers. Charging it before the
            // throw leaves that turn on the lesson through `LessonState`'s own REQUIRES_NEW transaction, which this
            // one's rollback cannot take back; without it a second-attempt 429 made the first turn free.
            try { r = call(system, u); }
            catch (RuntimeException e) { if (usage > 0) state.addUsage(lesson.getId(), usage, 0); throw e; }
            usage += r.total();
            ObjectNode candidate;
            try {
                candidate = (ObjectNode) json.tree(r.text());
                // the two fields the teacher's text may never move, pinned before the repairs so the branch they select is hers
                candidate.put("type", se.getType()); candidate.put("id", stopId);
                candidate = (ObjectNode) json.tree(LlmJson.dropUnknownStopFields(LlmJson.cleanIllustrations(candidate.toString())));
            } catch (Exception e) { errors = List.of("the answer was not a single JSON object"); continue; }
            if (candidate.has("stops")) { errors = List.of("answer with the one stop, not the whole play"); continue; }
            ObjectNode trial = playNode.deepCopy(); ((ArrayNode) trial.get("stops")).set(index, candidate);
            List<String> wrong = LlmJson.stopErrors(trial.toString(), index, stopId, pe.getLevel(), "manual".equals(lesson.getSource()));
            if (wrong.isEmpty()) { accepted = candidate; break; }
            errors = wrong;
            log.warn("Prompt D stop {} attempt {} invalid: {}", stopId, attempt + 1, errors);
            LlmFailures.keep("D-" + stopId, r.text(), errors);
        }
        if (accepted == null) {
            // this transaction is about to roll back, so the turns are charged through `LessonState`'s own
            // REQUIRES_NEW one: the provider billed for them whether or not anything could be stored
            state.addUsage(lesson.getId(), usage, 0);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, REPHRASE,
                    "Couldn't save, please rephrase. " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
        }

        playStops.set(index, accepted);
        Play updated = json.decodeShared(playNode.toString(), Play.Companion.serializer());
        store.savePlay(lesson.getId(), updated, pe.getPromptVersion(), pe.getSeed());
        // on the way through, the usage goes on the lesson this transaction already holds: a REQUIRES_NEW update
        // would be overwritten by the flush of this managed row at commit
        lesson.setTokenUsage(lesson.getTokenUsage() + usage); lesson.setUpdatedAt(Instant.now()); lessons.save(lesson);
        // after `savePlay` the stop rows are new ones: the text belongs to the JSON that was just written
        StopEntity saved = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("stop"));
        saved.setText(text); saved.setTextUpdatedAt(Instant.now()); stops.save(saved);
        return StopKt.withTeacherText(json.decodeShared(accepted.toString(), Stop.Companion.serializer()), text);
    }

    private LlmClient.Result call(String system, String user) {
        try { return llm.complete(system, user, List.of()); }
        catch (LlmClient.LlmException e) {
            if (e.isTransient()) throw new ApiException(HttpStatus.BAD_GATEWAY, quest.api.dto.ApiError.MODEL_FAILED, "The AI is busy — try that save again in a moment.");
            throw new ApiException(HttpStatus.BAD_GATEWAY, quest.api.dto.ApiError.MODEL_FAILED, e.getMessage());
        }
    }

    /**
     * The exact `$defs.stop_&lt;type&gt;` branch of the shared `Play.schema.json`, carrying the definitions it (and
     * they, transitively) reference — an exit ticket's branch reaches the answer-stop branches — so the model is
     * handed a schema it can actually satisfy rather than a fragment with dangling `$ref`s.
     */
    String branchSchema(String type) {
        JsonNode schema = json.tree(Schemas.INSTANCE.getPlay());
        JsonNode branch = schema.path("$defs").path("stop_" + type);
        if (branch.isMissingNode()) throw ApiException.badRequest("Unknown stop type: " + type);
        ObjectNode out = branch.deepCopy();
        ObjectNode defs = json.object();
        Deque<String> todo = new ArrayDeque<>(refsIn(branch)); Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!seen.add(name)) continue;
            JsonNode def = schema.path("$defs").path(name);
            if (def.isMissingNode()) continue;
            defs.set(name, def); todo.addAll(refsIn(def));
        }
        if (!defs.isEmpty()) out.set("$defs", defs);
        return out.toPrettyString();
    }

    private static List<String> refsIn(JsonNode node) {
        List<String> names = new ArrayList<>();
        Matcher m = REF.matcher(node.toString());
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
