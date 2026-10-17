package quest.server.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import quest.api.CacheKeys;
import quest.api.dto.Play;
import quest.api.dto.Stop;
import quest.api.validation.ValidationResult;
import quest.server.ai.AiFailure;
import quest.server.ai.AiService;
import quest.server.ai.AiTask;
import quest.server.ai.AiVerdict;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.hints.Entities.PracticePlayEntity;
import quest.server.hints.Entities.StopHintsEntity;
import quest.server.hints.HintRules;
import quest.server.hints.PracticePlayRepository;
import quest.server.hints.StopHintsRepository;

/**
 * Feature 30: the body of the `HINTS` pipeline step — two graded hints for every answerable stop of the lesson, and
 * one short practice set per confirmed skill.
 *
 * <p><strong>This is the only place in the server that writes either of them, and it runs only from the pipeline.</strong>
 * That is the owner's rule: generation happens at lesson build time and nowhere else, so no child-facing route can
 * ever reach a model — `ChildEndpointsHaveNoAiTest` and `NoAiFromChildEndpointsTest` are what keep it true, over the
 * call graph and at run time.
 *
 * <p>It lives in this package rather than in `quest.server.hints` for one reason: the play a practice set must be is
 * Prompt B's play, and {@link LlmJson}'s repairs and validator — the very ones Prompt B's own answers go through —
 * are package-private. The rows it writes, the teacher's view of them and the approvals are
 * {@link quest.server.hints.SmartHints}'s.
 *
 * <p>Nothing here is cached in `generation_cache`. A level is the same for the same slides, which is what makes
 * Prompt B's cache worth having; hints are keyed to <em>these</em> stop ids, which a regenerate changes, and a
 * practice set is one of several a teacher chooses between, so a cache hit would hand her the same one twice.
 */
@Service
public class SmartHintsGenerator {
    private static final Logger log = LoggerFactory.getLogger(SmartHintsGenerator.class);
    /** How many skills of one lesson get a practice set. Three is the cap on a lesson's levels, and on this. */
    static final int MAX_PRACTICE = 3;
    /** Ask, then ask once more with the rejected hints named. The second answer is read leniently — see {@link #readHints}. */
    static final int HINT_ATTEMPTS = 2;

    private final LessonStore store; private final PlayRepository plays; private final SkillRepository skills;
    private final AnalysisCacheRepository analyses; private final AnalysisService analysisService;
    private final StopHintsRepository hints; private final PracticePlayRepository practice;
    private final AiService ai; private final Json json; private final LessonState state;
    /** `created_at` of both tables comes from here, as `SmartHints.save`'s does: one time source, pinnable in a test. */
    private final java.time.Clock clock;

    public SmartHintsGenerator(LessonStore store, PlayRepository plays, SkillRepository skills, AnalysisCacheRepository analyses,
                               AnalysisService analysisService, StopHintsRepository hints, PracticePlayRepository practice,
                               AiService ai, Json json, LessonState state, java.time.Clock clock) {
        this.store = store; this.plays = plays; this.skills = skills; this.analyses = analyses; this.analysisService = analysisService;
        this.hints = hints; this.practice = practice; this.ai = ai; this.json = json; this.state = state; this.clock = clock;
    }

    /**
     * The step. Hints first, because they are what every stop needs; then one practice set per skill, each one
     * independent — a skill whose set the model could not write twice is logged and skipped rather than costing the
     * lesson its hints. A provider that is busy or unreachable is thrown on, so {@link LessonSteps} retries the step.
     */
    public void generate(LessonEntity lesson) {
        var levels = store.plays(lesson.getId()).stream().filter(p -> p.getVariant() <= 1).toList();
        if (levels.isEmpty()) throw ApiException.badRequest("Write the levels first.");
        var confirmed = skills.findByLessonIdAndConfirmedTrueOrderByPosition(lesson.getId());
        writeHints(lesson, levels);
        if (confirmed.isEmpty()) { log.info("lesson {} has no confirmed skill, so no practice set", lesson.getId()); return; }
        String hash = hash(lesson);
        String analysisJson = hash == null ? null : analyses.findById(CacheKeys.INSTANCE.analysisKey(hash))
                .map(CacheEntities.AnalysisCacheEntity::getAnalysisJson).orElse(null);
        if (analysisJson == null) { log.info("lesson {} has no analysis, so no practice set", lesson.getId()); return; }
        Set<String> excluded = canonicalLevelIds(lesson, levels);
        int variant = Play.PRACTICE_VARIANT;
        for (SkillEntity skill : confirmed.subList(0, Math.min(MAX_PRACTICE, confirmed.size()))) {
            try { writePractice(lesson, skill, analysisJson, excluded, variant++); }
            catch (ApiException e) { log.warn("lesson {}: no practice set for skill {} ({})", lesson.getId(), skill.getId(), e.getMessage()); }
        }
    }

    // ---------------------------------------------------------------- the hints
    private void writeHints(LessonEntity lesson, List<quest.server.content.Entities.PlayEntity> levels) {
        Map<String, Stop> answerable = new LinkedHashMap<>();
        var questions = json.array();
        for (var entity : levels) {
            Play play = store.play(entity);
            for (Stop stop : HintRules.answerableStops(play)) {
                answerable.put(stop.getId(), stop);
                var o = questions.addObject();
                o.put("stopId", stop.getId()); o.put("level", entity.getLevel()); o.put("variant", entity.getVariant());
                o.put("type", stop.getType()); o.put("question", HintRules.question(stop));
                var doNotSay = o.putArray("doNotSay");
                HintRules.answerWords(stop).forEach(doNotSay::add);
            }
        }
        if (answerable.isEmpty()) { log.info("lesson {} has no answerable stop, so no hints", lesson.getId()); return; }
        String user = Prompts.userSmartHints(skillsJson(lesson), questions.toString());
        long[] usage = {0};
        ObjectNode answer;
        try {
            answer = (ObjectNode) ai.run(AiTask.of("smartHints", "hints", lesson.getSchoolId(), Prompts.SMART_HINTS, user)
                    .attempts(HINT_ATTEMPTS)
                    .retrying(AiTask.Retry.saying("These hints were rejected:", "Answer again with every question's hints, corrected."))
                    .reading((raw, attempt) -> readHints(raw, answerable, attempt))
                    .keepingRejects()
                    .billing((in, out) -> usage[0] += in + out)).json();
        } catch (AiFailure f) {
            if (usage[0] > 0) state.addUsage(lesson.getId(), usage[0], 0);
            if (f.kind() == AiFailure.Kind.INVALID) throw new ApiException(HttpStatus.BAD_GATEWAY, "model_failed", "The hints didn't follow the rules: " + f.errorsJoined(5));
            throw transientOrFailed(f);
        } catch (RuntimeException e) { if (usage[0] > 0) state.addUsage(lesson.getId(), usage[0], 0); throw e; }
        state.addUsage(lesson.getId(), usage[0], 0);
        save(lesson, answer, answerable.keySet());
    }

    /**
     * One raw answer: every question's two hints, each inside {@link HintRules}.
     *
     * <p>The last attempt is read differently, and deliberately. A hint that gives the answer away is rejected and
     * asked for again, because that is the one rule the feature exists for; a model that writes it twice loses
     * <em>that stop's</em> hints and nothing else. Failing the whole step over one stop would cost the teacher the
     * other thirty, and leaving the bad one in would cost a child the question.
     */
    private AiVerdict readHints(String raw, Map<String, Stop> answerable, int attempt) {
        JsonNode node;
        try { node = json.tree(raw); } catch (RuntimeException e) { return AiVerdict.rejected("not valid JSON"); }
        if (!(node.get("hints") instanceof ArrayNode written)) return AiVerdict.rejected("answer with one object holding a \"hints\" array");
        Map<String, List<String>> byStop = new LinkedHashMap<>();
        for (JsonNode entry : written) {
            String stopId = entry.path("stopId").asText("");
            if (!answerable.containsKey(stopId) || entry.path("hints").isMissingNode()) continue;
            var list = new ArrayList<String>();
            for (JsonNode hint : entry.path("hints")) if (hint.isTextual()) list.add(hint.asText().strip());
            byStop.put(stopId, list);
        }
        var errors = new ArrayList<String>();
        var good = json.array();
        for (var e : answerable.entrySet()) {
            var wrote = byStop.get(e.getKey());
            var stopErrors = wrote == null ? List.of(e.getKey() + ": no hints at all") : HintRules.errors(e.getKey(), wrote, HintRules.answerWords(e.getValue()));
            if (stopErrors.isEmpty()) {
                var o = good.addObject(); o.put("stopId", e.getKey());
                var arr = o.putArray("hints"); wrote.forEach(arr::add);
            } else errors.addAll(stopErrors);
        }
        boolean last = attempt + 1 >= HINT_ATTEMPTS;
        if (!errors.isEmpty() && !last) return AiVerdict.rejected(errors.subList(0, Math.min(8, errors.size())));
        if (!errors.isEmpty()) log.warn("dropped the hints of {} stop(s) the model would not fix: {}", errors.size(), errors.subList(0, Math.min(5, errors.size())));
        if (good.isEmpty()) return AiVerdict.rejected(errors.isEmpty() ? List.of("no hints at all") : errors.subList(0, Math.min(8, errors.size())));
        var out = json.object();
        out.set("hints", good);
        return AiVerdict.ok(out);
    }

    /** The accepted hints, unapproved, replacing whatever this lesson had. */
    private void save(LessonEntity lesson, ObjectNode answer, Set<String> answerable) {
        var existing = new HashSet<String>();
        hints.findByLessonId(lesson.getId()).forEach(row -> existing.add(row.getStopId()));
        int written = 0;
        for (JsonNode entry : answer.path("hints")) {
            String stopId = entry.path("stopId").asText("");
            if (!answerable.contains(stopId)) continue;
            var row = hints.findById(stopId).orElseGet(StopHintsEntity::new);
            row.setStopId(stopId); row.setLessonId(lesson.getId()); row.setSchoolId(lesson.getSchoolId());
            row.setHintsJson(entry.path("hints").toString());
            row.setApprovedBy(null); row.setApprovedAt(null);          // new hints are nobody's yet
            if (row.getCreatedAt() == null) row.setCreatedAt(clock.instant());
            hints.save(row);
            existing.remove(stopId);
            written++;
        }
        // a stop whose hints were dropped this time keeps none: an old row would be hints about an old question
        existing.forEach(hints::deleteById);
        log.info("lesson {}: wrote hints for {} stop(s)", lesson.getId(), written);
    }

    // ---------------------------------------------------------------- the practice sets
    private void writePractice(LessonEntity lesson, SkillEntity skill, String analysisJson, Set<String> excluded, int variant) {
        String user = Prompts.userTargetedPractice(variant, skillJson(skill), analysisJson, new ArrayList<>(excluded));
        long[] usage = {0}; String text;
        try {
            text = ai.run(AiTask.of("smartHints", "practice-v" + variant, lesson.getSchoolId(), Prompts.TARGETED_PRACTICE, user)
                    .reading((raw, attempt) -> {
                        String cleaned = LlmJson.dropUnknownStopFields(LlmJson.cleanIllustrations(raw));
                        ValidationResult v = LlmJson.validatePlay(cleaned, 1, excluded);
                        return v.getErrors().isEmpty() ? AiVerdict.ok(cleaned) : AiVerdict.rejected(v.getErrors());
                    })
                    .keepingRejects()
                    .billing((in, out) -> usage[0] += in + out)).text();
        } catch (AiFailure f) {
            if (usage[0] > 0) state.addUsage(lesson.getId(), usage[0], 0);
            if (f.kind() == AiFailure.Kind.INVALID) throw new ApiException(HttpStatus.BAD_GATEWAY, "model_failed", "the practice set didn't match the schema: " + f.errorsJoined(3));
            throw transientOrFailed(f);
        } catch (RuntimeException e) { if (usage[0] > 0) state.addUsage(lesson.getId(), usage[0], 0); throw e; }
        state.addUsage(lesson.getId(), usage[0], 0);
        var node = (ObjectNode) json.tree(text);
        node.put("level", 1); node.put("variant", variant);
        StopIds.relabel(node, lesson.getId(), 1, variant);
        Play play = json.decodeShared(node.toString(), Play.Companion.serializer());
        var entity = store.savePlay(lesson.getId(), play, CacheKeys.PROMPT_B_VERSION, 0);
        var row = practice.findById(entity.getId()).orElseGet(PracticePlayEntity::new);
        row.setPlayId(entity.getId()); row.setLessonId(lesson.getId()); row.setSchoolId(lesson.getSchoolId()); row.setSkillId(skill.getId());
        row.setApprovedBy(null); row.setApprovedAt(null);
        if (row.getCreatedAt() == null) row.setCreatedAt(clock.instant());
        practice.save(row);
        log.info("lesson {}: wrote a practice set for skill {} as variant {}", lesson.getId(), skill.getId(), variant);
    }

    // ---------------------------------------------------------------- helpers
    /** The stop ids of the levels as the model wrote them (no lesson prefix) — what a practice set may not reuse. */
    private Set<String> canonicalLevelIds(LessonEntity lesson, List<quest.server.content.Entities.PlayEntity> levels) {
        Set<String> out = new HashSet<>();
        for (var entity : levels) {
            String prefix = StopIds.prefix(lesson.getId(), entity.getLevel(), entity.getVariant());
            for (Stop s : store.play(entity).getStops()) {
                out.add(strip(s.getId(), prefix));
                if (s instanceof Stop.ExitTicket et) for (Stop q : et.getQuestions()) out.add(strip(q.getId(), prefix));
            }
        }
        return out;
    }

    private static String strip(String id, String prefix) { return id.startsWith(prefix) ? id.substring(prefix.length()) : id; }

    private String skillsJson(LessonEntity lesson) {
        var arr = json.array();
        for (SkillEntity s : skills.findByLessonIdAndConfirmedTrueOrderByPosition(lesson.getId())) {
            var o = arr.addObject(); o.put("name", s.getName()); o.put("subject", s.getSubject()); o.put("method", s.getMethod());
        }
        return arr.isEmpty() ? "[]" : arr.toString();
    }

    private String skillJson(SkillEntity skill) {
        var o = json.object();
        o.put("name", skill.getName()); o.put("subject", skill.getSubject()); o.put("method", skill.getMethod());
        o.set("examples", json.tree(skill.getExamplesJson()));
        return o.toString();
    }

    private String hash(LessonEntity lesson) {
        return lesson.getSourceHash() == null ? analysisService.sourceHash(lesson) : lesson.getSourceHash();
    }

    /** A provider that failed: the step retries a busy one and then says `model_unavailable`; anything else is 502. */
    private static RuntimeException transientOrFailed(AiFailure f) {
        if (f.isTransient()) return new LessonSteps.TransientFailure(f.getMessage(), f.getCause());
        return new ApiException(HttpStatus.BAD_GATEWAY, "model_failed", f.getMessage());
    }
}
