package quest.server.analysis;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import quest.api.CacheKeys;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.Stop;
import quest.api.validation.SchemaValidator;
import quest.api.validation.ValidationResult;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PlayEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonStore;
import quest.server.content.SkillRepository;

/**
 * Prompt B (one play per level + the Level-1 "Again" variant) and Prompt C (parent panel), each cache-first on
 * (sourceHash, level, variant, seed, promptVersion). Cached JSON keeps the model's short ids; ids are made
 * lesson-unique when the play is attached to a lesson.
 */
@Service
public class GenerationService {
    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    static final int[][] TARGETS = {{1, 0}, {2, 0}, {3, 0}, {1, 1}};

    private final GenerationCacheRepository cache; private final AnalysisCacheRepository analyses; private final SkillRepository skills;
    private final LessonStore store; private final LlmClient llm; private final Json json; private final LessonState state; private final AnalysisService analysisService;
    private final quest.server.content.ParentPanelRepository panels;

    public GenerationService(GenerationCacheRepository cache, AnalysisCacheRepository analyses, SkillRepository skills, LessonStore store, LlmClient llm, Json json, LessonState state, AnalysisService analysisService, quest.server.content.ParentPanelRepository panels) {
        this.cache = cache; this.analyses = analyses; this.skills = skills; this.store = store; this.llm = llm; this.json = json; this.state = state; this.analysisService = analysisService; this.panels = panels;
    }

    /** One play (a pipeline step): cache-first on the source hash; the Again variant excludes the stored Level 1's ids. */
    public void generatePlay(LessonEntity lesson, int level, int variant) {
        String hash = requireHash(lesson);
        Set<String> excluded = variant == 1 ? store.plays(lesson.getId()).stream().filter(p -> p.getLevel() == 1 && p.getVariant() == 0).findFirst().map(p -> canonicalIds(store.play(p), lesson, 1, 0)).orElse(Set.of()) : Set.of();
        String canonical = playJson(lesson, hash, analysisJson(hash), confirmedSkillsJson(lesson), level, variant, 0, excluded);
        attach(lesson, canonical, level, variant, 0);
    }

    /** The parent panel (a pipeline step) from the three stored levels. */
    public void generatePanel(LessonEntity lesson) {
        String hash = requireHash(lesson);
        // Prompt C and the panel relabeller work on canonical plays (the model's short stop ids): strip the lesson prefix
        Map<String, String> plays = new HashMap<>();
        for (var p : store.plays(lesson.getId())) if (p.getVariant() == 0) plays.put(p.getLevel() + ":0", p.getPlayJson().replace(StopIds.prefix(lesson.getId(), p.getLevel(), 0), ""));
        if (plays.size() < 3) throw ApiException.badRequest("Write the three levels first.");
        panel(lesson, hash, analysisJson(hash), plays);
    }

    /** All four plays and the panel for a lesson (called by the pipeline after skills are confirmed). */
    public void generateAll(LessonEntity lesson) {
        String hash = requireHash(lesson);
        String analysisJson = analysisJson(hash);
        String skillsJson = confirmedSkillsJson(lesson);
        Map<String, String> canonicalPlays = new HashMap<>();
        Set<String> level1Ids = new HashSet<>();
        for (int[] t : TARGETS) {
            int level = t[0], variant = t[1];
            String canonical = playJson(lesson, hash, analysisJson, skillsJson, level, variant, 0, variant == 1 ? level1Ids : Set.of());
            if (level == 1 && variant == 0) level1Ids.addAll(stopIds(canonical));
            canonicalPlays.put(level + ":" + variant, canonical);
            attach(lesson, canonical, level, variant, 0);
        }
        panel(lesson, hash, analysisJson, canonicalPlays);
    }

    /** Regenerate one level with the next seed (a fresh cache slot). */
    public Play regeneratePlay(LessonEntity lesson, PlayEntity existing) {
        String hash = requireHash(lesson);
        int seed = existing.getSeed() + 1;
        Set<String> excluded = existing.getVariant() == 1 ? store.plays(lesson.getId()).stream().filter(p -> p.getLevel() == 1 && p.getVariant() == 0).findFirst().map(p -> canonicalIds(store.play(p), lesson, 1, 0)).orElse(Set.of()) : Set.of();
        String canonical = playJson(lesson, hash, analysisJson(hash), confirmedSkillsJson(lesson), existing.getLevel(), existing.getVariant(), seed, excluded);
        return attach(lesson, canonical, existing.getLevel(), existing.getVariant(), seed);
    }

    /** Rewrite one stop in place (never cached — each press is a fresh idea). */
    public Stop regenerateStop(LessonEntity lesson, PlayEntity playEntity, String stopId) {
        Play play = store.play(playEntity);
        ObjectNode playNode = (ObjectNode) json.tree(playEntity.getPlayJson());
        ArrayNode stops = (ArrayNode) playNode.get("stops");
        int index = -1;
        for (int i = 0; i < stops.size(); i++) if (stopId.equals(stops.get(i).path("id").asText())) index = i;
        if (index < 0) throw ApiException.notFound("stop");
        Set<String> used = new HashSet<>(); for (Stop s : play.getStops()) { used.add(s.getId()); if (s instanceof Stop.ExitTicket et) et.getQuestions().forEach(q -> used.add(q.getId())); }
        String user = Prompts.userStop(playNode.toString(), stops.get(index).toString(), new ArrayList<>(used));
        long usage = 0; List<String> errors = List.of(); ObjectNode replacement = null;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String u = attempt == 0 ? user : user + "\n\nYour previous stop was rejected:\n- " + String.join("\n- ", errors) + "\nAnswer again with the corrected stop JSON only.";
                LlmClient.Result r = call(Prompts.SYSTEM_B, u);
                usage += r.total();
                ObjectNode candidate;
                try { candidate = (ObjectNode) json.tree(LlmJson.dropUnknownStopFields(LlmJson.cleanIllustrations(r.text()))); } catch (Exception e) { errors = List.of("not valid JSON"); continue; }
                if (candidate.has("stops")) { errors = List.of("answer with the single stop, not the whole play"); continue; }
                StopIds.relabelStop(candidate, StopIds.prefix(lesson.getId(), playEntity.getLevel(), playEntity.getVariant()));
                if (used.contains(candidate.path("id").asText())) candidate.put("id", candidate.path("id").asText() + "r" + (attempt + 1));
                ObjectNode trial = playNode.deepCopy(); ((ArrayNode) trial.get("stops")).set(index, candidate);
                ValidationResult v = LlmJson.validatePlay(trial.toString(), playEntity.getLevel(), Set.of());
                if (v.getErrors().isEmpty()) { replacement = candidate; break; }
                errors = v.getErrors();
            }
        } catch (RuntimeException e) { if (usage > 0) state.addUsage(lesson.getId(), usage, 0); throw e; }
        state.addUsage(lesson.getId(), usage, 0);
        if (replacement == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "model_failed", "Couldn't regenerate this stop: " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
        stops.set(index, replacement);
        Play updated = json.decodeShared(playNode.toString(), Play.Companion.serializer());
        store.savePlay(lesson.getId(), updated, playEntity.getPromptVersion(), playEntity.getSeed());
        return json.decodeShared(replacement.toString(), Stop.Companion.serializer());
    }

    // ---------------------------------------------------------------- Prompt B
    private String playJson(LessonEntity lesson, String hash, String analysisJson, String skillsJson, int level, int variant, int seed, Set<String> excludedIds) {
        String key = CacheKeys.INSTANCE.playKey(hash, level, variant, seed);
        var cached = cache.findById(key).orElse(null);
        if (cached != null) { cached.setHits(cached.getHits() + 1); cache.save(cached); state.addUsage(lesson.getId(), 0, cached.getTokenUsage()); log.info("play cache hit {}", key); return cached.getJson(); }
        String user = Prompts.userB(level, variant, analysisJson, skillsJson, lesson.getNotes(), lesson.getPracticeLength(), new ArrayList<>(excludedIds));
        if (seed > 0) user += "\n\nThis is regeneration #" + seed + ": make every question different from what you would write first.";
        long usage = 0; List<String> errors = List.of(); String text = null;
        // As in `AnalysisService.promptA`: a first attempt that was answered and paid for is booked even when the
        // second one never comes back, so an abandoned step still shows what it cost.
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String u = attempt == 0 ? user : user + "\n\nYour previous answer was rejected by the validator:\n- " + String.join("\n- ", errors) + "\nAnswer again with corrected JSON only.";
                LlmClient.Result r = call(Prompts.SYSTEM_B, u);
                usage += r.total();
                String cleaned = LlmJson.dropUnknownStopFields(LlmJson.cleanIllustrations(r.text()));
                ValidationResult v = LlmJson.validatePlay(cleaned, level, excludedIds);
                if (v.getErrors().isEmpty()) { text = cleaned; break; }
                errors = v.getErrors(); log.warn("Prompt B L{}v{} attempt {} invalid: {}", level, variant, attempt + 1, errors);
                LlmFailures.keep("B-L" + level + "v" + variant, r.text(), errors);
            }
        } catch (RuntimeException e) { if (usage > 0) state.addUsage(lesson.getId(), usage, 0); throw e; }
        state.addUsage(lesson.getId(), usage, 0);
        if (text == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "model_failed", "Level " + level + " didn't match the schema: " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
        var e = new CacheEntities.GenerationCacheEntity();
        e.setCacheKey(key); e.setSourceHash(hash); e.setKind("play"); e.setPromptVersion(CacheKeys.PROMPT_B_VERSION); e.setJson(text); e.setTokenUsage(usage); e.setHits(0); e.setCreatedAt(Instant.now());
        cache.save(e);
        return text;
    }

    private Play attach(LessonEntity lesson, String canonicalJson, int level, int variant, int seed) {
        ObjectNode node = (ObjectNode) json.tree(canonicalJson);
        node.put("level", level); node.put("variant", variant);
        StopIds.relabel(node, lesson.getId(), level, variant);
        Play play = json.decodeShared(node.toString(), Play.Companion.serializer());
        store.savePlay(lesson.getId(), play, CacheKeys.PROMPT_B_VERSION, seed);
        return play;
    }

    // ---------------------------------------------------------------- Prompt C
    private void panel(LessonEntity lesson, String hash, String analysisJson, Map<String, String> canonicalPlays) {
        String key = CacheKeys.INSTANCE.panelKey(hash);
        var cached = cache.findById(key).orElse(null);
        String text;
        if (cached != null) { cached.setHits(cached.getHits() + 1); cache.save(cached); state.addUsage(lesson.getId(), 0, cached.getTokenUsage()); text = cached.getJson(); }
        else {
            var plays = json.array();
            for (int[] t : TARGETS) if (t[1] == 0) plays.add(json.tree(canonicalPlays.get(t[0] + ":0")));
            String user = Prompts.userC(analysisJson, plays.toString());
            long usage = 0; List<String> errors = List.of(); text = null;
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    String u = attempt == 0 ? user : user + "\n\nYour previous answer was rejected:\n- " + String.join("\n- ", errors) + "\nAnswer again with corrected JSON only.";
                    LlmClient.Result r = call(Prompts.SYSTEM_C, u);
                    usage += r.total();
                    ValidationResult v = SchemaValidator.INSTANCE.validatePanelJson(r.text());
                    if (v.getErrors().isEmpty()) { text = r.text(); break; }
                    errors = v.getErrors(); LlmFailures.keep("C", r.text(), errors);
                }
            } catch (RuntimeException e) { if (usage > 0) state.addUsage(lesson.getId(), usage, 0); throw e; }
            state.addUsage(lesson.getId(), usage, 0);
            if (text == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "model_failed", "The parent panel didn't match the schema: " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
            var e = new CacheEntities.GenerationCacheEntity();
            e.setCacheKey(key); e.setSourceHash(hash); e.setKind("panel"); e.setPromptVersion(CacheKeys.PROMPT_C_VERSION); e.setJson(text); e.setTokenUsage(usage); e.setHits(0); e.setCreatedAt(Instant.now());
            cache.save(e);
        }
        // "L<level>:<id>" in the panel → lesson-unique stop ids (the panel was written against the canonical plays)
        Map<Integer, Set<String>> idsByLevel = new HashMap<>();
        for (int[] t : TARGETS) if (t[1] == 0) idsByLevel.put(t[0], new HashSet<>(stopIds(canonicalPlays.get(t[0] + ":0"))));
        ObjectNode node = (ObjectNode) json.tree(text);
        StopIds.relabelPanel(node, lesson.getId(), idsByLevel);
        store.savePanel(lesson.getId(), json.decodeShared(node.toString(), ParentPanel.Companion.serializer()));
    }

    // ---------------------------------------------------------------- helpers
    private LlmClient.Result call(String system, String user) {
        try { return llm.complete(system, user, List.of()); }
        catch (LlmClient.LlmException e) { if (e.isTransient()) throw new LessonSteps.TransientFailure(e.getMessage(), e); throw new ApiException(HttpStatus.BAD_GATEWAY, "model_failed", e.getMessage()); }
    }

    private String requireHash(LessonEntity lesson) {
        String hash = lesson.getSourceHash() == null ? analysisService.sourceHash(lesson) : lesson.getSourceHash();
        if (hash == null) throw ApiException.badRequest("Upload and analyse the slides first.");
        return hash;
    }

    private String analysisJson(String hash) {
        return analyses.findById(CacheKeys.INSTANCE.analysisKey(hash)).map(CacheEntities.AnalysisCacheEntity::getAnalysisJson).orElseThrow(() -> ApiException.badRequest("Analyse the slides first."));
    }

    private String confirmedSkillsJson(LessonEntity lesson) {
        var arr = json.array();
        for (SkillEntity s : skills.findByLessonIdAndConfirmedTrueOrderByPosition(lesson.getId())) {
            var o = arr.addObject(); o.put("id", s.getId().substring(s.getId().indexOf(':') + 1)); o.put("name", s.getName()); o.put("subject", s.getSubject()); o.put("method", s.getMethod());
            o.set("examples", json.tree(s.getExamplesJson()));
        }
        if (arr.isEmpty()) throw ApiException.badRequest("Confirm at least one skill first.");
        return arr.toString();
    }

    static List<String> stopIds(String playJson) {
        List<String> ids = new ArrayList<>();
        Play play = SchemaValidator.INSTANCE.getJson().decodeFromString(Play.Companion.serializer(), playJson);
        for (Stop s : play.getStops()) { ids.add(s.getId()); if (s instanceof Stop.ExitTicket et) for (Stop q : et.getQuestions()) ids.add(q.getId()); }
        return ids;
    }

    private static Set<String> canonicalIds(Play play, LessonEntity lesson, int level, int variant) {
        String prefix = StopIds.prefix(lesson.getId(), level, variant); Set<String> out = new HashSet<>();
        for (Stop s : play.getStops()) { out.add(strip(s.getId(), prefix)); if (s instanceof Stop.ExitTicket et) for (Stop q : et.getQuestions()) out.add(strip(q.getId(), prefix)); }
        return out;
    }
    private static String strip(String id, String prefix) { return id.startsWith(prefix) ? id.substring(prefix.length()) : id; }
}
