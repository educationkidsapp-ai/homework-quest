package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.api.dto.Stop;
import quest.server.ClassFixtures;
import quest.server.analysis.Prompts;

/**
 * E5 (D27): "Add level → Let the assistant write it" in the write-it-yourself flow. The teacher writes Level 1 stop
 * by stop and asks for one more level; the server runs a single ledger step for it and the lesson comes back to
 * `review`, which is all the editor's poll and the bell already understand.
 *
 * <p>The lesson these tests use has <strong>no analysis at all</strong> — she never pressed "Generate the other
 * levels" — so the interesting half is the derivation: Prompt A is run over her Level 1 stops read out as English,
 * and only then is the level asked for. Everything else here is the refusals, because a level is expensive: a job
 * already running is a 409, a level that already has questions is a 409 unless `replace` says otherwise, an empty
 * Level 1 is a 400, and a colleague's lesson is refused before any of it.
 */
class AddLevelTest extends TeacherTestSupport {
    private static final String SCHOOL = "al-school";
    private static final String TEACHER = "al-teacher", OTHER = "al-other";
    private static final String HERS = "al-class-1a", THEIRS = "al-class-1b";

    @Override String prefix() { return "al-"; }

    private String teacherToken, otherToken;

    @org.springframework.beans.factory.annotation.Autowired quest.server.notifications.NotificationRepository bellRows;
    @org.springframework.beans.factory.annotation.Autowired quest.server.content.SourceFileRepository sourceFiles;
    @org.springframework.beans.factory.annotation.Autowired quest.server.analysis.AnalysisCacheRepository analysisCache;
    @org.springframework.beans.factory.annotation.Autowired quest.server.content.LessonStepRepository stepRows;

    @BeforeEach void seed() {
        school(SCHOOL, "Add Level Academy", "ALSCH1");
        // the email is the token's, because a notification's recipient is resolved from `lessons.created_by`
        teacher(TEACHER, SCHOOL, TEACHER + "@seed.test", "Ms Hana", "[\"english\"]", "british", "[1]");
        teacher(OTHER, SCHOOL, OTHER + "@seed.test", "Ms Noor", "[\"english\"]", "british", "[1]");
        section(HERS, "1A", TEACHER); section(THEIRS, "1B", OTHER);
        teacherToken = token(TEACHER, "TEACHER", SCHOOL);
        otherToken = token(OTHER, "TEACHER", SCHOOL);
    }

    @AfterEach void clean() {
        removeSeed();
        bellRows.deleteAll(bellRows.findAll().stream().filter(r -> r.getSchoolId() != null && r.getSchoolId().startsWith(prefix())).toList());
        sourceFiles.deleteAll(sourceFiles.findAll().stream().filter(f -> f.getId().startsWith(prefix())).toList());
        analysisCache.deleteAll(analysisCache.findAll().stream().filter(c -> c.getSourceHash().startsWith(prefix())).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getClassId().startsWith(prefix())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getId().startsWith(prefix())).toList());
    }

    /** The whole of it on one hand-written lesson: Level 2, then `exists`, then `replace`, then Again. */
    @Test void one_level_is_written_from_the_hand_written_level_one() throws Exception {
        String id = handWritten(0);
        var before = levelOne(id);
        assertThat(before).hasSize(3);

        assertThat(generate(id, "2", false)).isEqualTo("generating");
        var ready = awaitReview(id);
        assertThat(stops(ready, 2, 0)).as("the assistant wrote Level 2").isNotEmpty();
        assertThat(levelOne(id)).as("Level 1 is untouched").isEqualTo(before);
        // the analysis was derived from her stops rather than from an upload, and its skills need no confirming
        var lesson = teacherLesson(id);
        assertThat(lesson.get("skills")).isNotEmpty();
        assertThat(lesson.get("skills").findValues("confidence").stream().allMatch(c -> c.asDouble() == 1.0)).isTrue();
        assertThat(step(lesson, "analyze")).isEqualTo("done");
        assertThat(step(lesson, "generate_L2")).isEqualTo("done");
        assertThat(step(lesson, "panel")).as("the panel waits for publish, as it does for any hand-written lesson").isEqualTo("pending");

        // asking again is refused, and `replace` is the way through
        var refused = json(mvc.perform(as(post(level(id, "2")), teacherToken)).andExpect(status().isConflict()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("exists");
        assertThat(generate(id, "2", true)).isEqualTo("generating");
        assertThat(stops(awaitReview(id), 2, 0)).isNotEmpty();

        // Level 3 end to end on the same lesson, from the analysis Level 2 derived
        assertThat(generate(id, "3", false)).isEqualTo("generating");
        var three = awaitReview(id);
        assertThat(stops(three, 3, 0)).isNotEmpty().doesNotContainAnyElementsOf(before);
        assertThat(step(three, "generate_L3")).isEqualTo("done");
        // and Regenerate on a level written this way keeps the harder-than-Level-1 exclusion (it is not variant 1)
        String playId = plays.findByLessonIdAndLevelAndVariant(id, 3, 0).orElseThrow().getId();
        var again3 = json(mvc.perform(as(post("/teacher/plays/" + playId + "/regenerate"), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(again3.get("stops")).isNotEmpty();

        // the Again variant: a second pass over Level 1, and none of Level 1's stops
        assertThat(generate(id, "again", false)).isEqualTo("generating");
        var withAgain = awaitReview(id);
        assertThat(stops(withAgain, 1, 1)).isNotEmpty();
        assertThat(stops(withAgain, 1, 1)).doesNotContainAnyElementsOf(before);

        // and the bell rang once per level that landed (E2: `generating` → `review` is a real transition)
        var bell = json(mvc.perform(as(get("/me/notifications"), teacherToken)).andExpect(status().isOk()).andReturn());
        var rang = new java.util.ArrayList<String>();
        bell.forEach(row -> { if ("lesson.ready".equals(row.get("kind").asText())) rang.add(row.path("lessonId").asText()); });
        assertThat(rang).as("one `lesson.ready` row per level that landed").contains(id);
    }

    /** The refusals that are not about the level: a job in flight, a Level 1 with nothing in it, somebody else's lesson. */
    @Test void a_running_job_an_empty_level_one_and_a_colleagues_lesson_are_all_refused() throws Exception {
        String id = handWritten(1);

        // a step of this lesson is running: 409 `generating`, not a second job
        var row = lessons.findById(id).orElseThrow();
        row.setStatus("generating"); row.setUpdatedAt(Instant.now()); lessons.save(row);
        var busy = json(mvc.perform(as(post(level(id, "3")), teacherToken)).andExpect(status().isConflict()).andReturn());
        assertThat(busy.get("code").asText()).isEqualTo("generating");
        row.setStatus("review"); row.setUpdatedAt(Instant.now()); lessons.save(row);

        // a level outside 2 / 3 / again (D27 caps them at three)
        mvc.perform(as(post(level(id, "4")), teacherToken)).andExpect(status().isBadRequest());
        mvc.perform(as(post(level(id, "1")), teacherToken)).andExpect(status().isBadRequest());

        // nothing to write from
        String empty = create(2, "Nothing written yet");
        mvc.perform(as(post(level(empty, "2")), teacherToken)).andExpect(status().isBadRequest());

        // a colleague is refused before anything is read, and a lesson that does not exist is the same silence
        mvc.perform(as(post(level(id, "2")), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post(level("al-nothing", "2")), teacherToken)).andExpect(status().isNotFound());
    }

    /**
     * The ledger is not the whole answer for a lesson that predates it. A lesson uploaded before `lesson_steps`
     * existed has no rows at all, and reading that as "never analysed" would re-analyse it from its Level 1 — which
     * replaces the skills its teacher confirmed, overwrites the `source_hash` every one of its plays is keyed on and
     * marks Upload and Convert done on a lesson that has files. Retry backfills first for exactly this reason, and so
     * must this.
     */
    @Test void a_lesson_with_no_ledger_rows_keeps_its_own_analysis_and_confirmed_skills() throws Exception {
        String id = handWritten(5);
        String hash = uploaded(id);                       // files + an analysis in the cache + one confirmed skill
        stepRows.deleteAll(stepRows.findByLessonIdOrderByPosition(id));   // …and no ledger, as before E1

        assertThat(generate(id, "2", false)).isEqualTo("generating");
        assertThat(stops(awaitReview(id), 2, 0)).isNotEmpty();

        assertThat(lessons.findById(id).orElseThrow().getSourceHash()).as("the hash its plays are keyed on").isEqualTo(hash);
        var kept = skills.findByLessonIdOrderByPosition(id);
        assertThat(kept).hasSize(1);
        assertThat(kept.getFirst().getName()).isEqualTo("Counting on");
        assertThat(kept.getFirst().isConfirmed()).isTrue();
    }

    /**
     * A derived analysis is an analysis of those stops, so it has to follow them. Three stops, a Level 2; three more
     * stops, a Level 3 — and Level 3 must be written from an analysis of all six, not of the first three. An
     * unchanged Level 1 is the other half of it: the analysis is not run again at all.
     */
    @Test void the_derived_analysis_follows_level_one() throws Exception {
        String id = handWritten(6);

        assertThat(generate(id, "2", false)).isEqualTo("generating");
        var first = awaitReview(id);
        String three = lessons.findById(id).orElseThrow().getSourceHash();
        assertThat(three).isNotNull();
        assertThat(attempts(first, "analyze")).isEqualTo(1);

        // Level 3 with the same Level 1: the same analysis, and Prompt A is not run a second time
        assertThat(generate(id, "3", false)).isEqualTo("generating");
        var same = awaitReview(id);
        assertThat(stops(same, 3, 0)).isNotEmpty();
        assertThat(lessons.findById(id).orElseThrow().getSourceHash()).isEqualTo(three);
        assertThat(attempts(same, "analyze")).as("an unchanged Level 1 costs nothing").isEqualTo(1);

        // three more stops, then Again: a new analysis, and all six ids are what it excludes
        addStops(id, "Pick the pot", "Pick the lid", "Pick the spoon");
        assertThat(levelOne(id)).hasSize(6);
        assertThat(generate(id, "again", false)).isEqualTo("generating");
        var grown = awaitReview(id);
        String six = lessons.findById(id).orElseThrow().getSourceHash();
        assertThat(six).as("the analysis followed the stops").isNotEqualTo(three);
        assertThat(attempts(grown, "analyze")).isEqualTo(2);
        assertThat(stops(grown, 1, 1)).isNotEmpty().doesNotContainAnyElementsOf(levelOne(id));

        // and `replace` after another edit is on the newest analysis too, not on the text of two edits ago
        addStops(id, "Pick the bowl");
        assertThat(generate(id, "2", true)).isEqualTo("generating");
        var replaced = awaitReview(id);
        assertThat(stops(replaced, 2, 0)).isNotEmpty();
        assertThat(lessons.findById(id).orElseThrow().getSourceHash()).isNotEqualTo(six);
        assertThat(attempts(replaced, "analyze")).isEqualTo(3);
    }

    /**
     * The one prompt change E5 needs: Levels 2 and 3 written from a hand-written Level 1 are told to be harder than
     * it and given its stop ids to avoid. The uploaded pipeline passes no ids for those levels, so its prompt — and
     * therefore its cache — is byte for byte what it was.
     */
    @Test void a_level_written_from_level_one_is_asked_to_be_harder_and_different() {
        String derived = Prompts.userB(2, 0, "{}", "[]", null, 7, List.of("m1", "m2"));
        assertThat(derived).contains("must be harder").contains("Do not reuse these stop ids: m1, m2");
        assertThat(derived).contains("already played this lesson's Level 1");
        assertThat(Prompts.userB(2, 0, "{}", "[]", null, 7, List.of())).doesNotContain("Do not reuse");
        assertThat(Prompts.userB(1, 1, "{}", "[]", null, 7, List.of("m1"))).contains("AGAIN variant");
    }

    // ---------------------------------------------------------------- helpers

    /** A lesson written by hand: created `manual` with an empty Level 1, then three stops in it. */
    private String handWritten(int day) throws Exception {
        String id = create(day, "Our class pet");
        addStops(id, "Pick the pet", "Pick the food", "Pick the day");
        return id;
    }

    /** Adds stops to the lesson's Level 1, the way the Add question sheet does. */
    private void addStops(String id, String... titles) throws Exception {
        String playId = plays.findByLessonIdAndLevelAndVariant(id, 1, 0).orElseThrow().getId();
        for (String title : titles)
            mvc.perform(as(post("/teacher/plays/" + playId + "/stops").contentType(MediaType.APPLICATION_JSON)
                    .content(json().encodeShared(choice("x", title), Stop.Companion.serializer())), teacherToken))
                    .andExpect(status().isOk());
    }

    /**
     * Makes the lesson look uploaded and analysed the way a pre-E1 lesson does: one active file, an analysis in the
     * permanent cache under the lesson's `source_hash`, and one skill its teacher confirmed. Answers the hash.
     */
    private String uploaded(String id) {
        String hash = prefix() + "hash-" + id.substring(0, 8);
        var file = new quest.server.content.Entities.SourceFileEntity();
        file.setId(prefix() + "file-" + id.substring(0, 8)); file.setLessonId(id); file.setFileName("slides.pdf");
        file.setFileHash(hash); file.setKind("pdf"); file.setMimeType("application/pdf"); file.setPageCount(2);
        file.setStoragePath("lessons/" + id + "/slides.pdf"); file.setSizeBytes(1024); file.setCacheHit(false);
        file.setConvertStatus("ready"); file.setCreatedAt(Instant.now());
        sourceFiles.save(file);

        var cached = new quest.server.analysis.CacheEntities.AnalysisCacheEntity();
        cached.setCacheKey(quest.api.CacheKeys.INSTANCE.analysisKey(hash)); cached.setSourceHash(hash);
        cached.setCurriculum("british"); cached.setGrade(1); cached.setSubject("english");
        cached.setPromptVersion(quest.api.CacheKeys.PROMPT_A_VERSION);
        cached.setAnalysisJson(quest.api.validation.SchemaValidator.INSTANCE.getJson()
                .encodeToString(quest.api.dto.SourceAnalysis.Companion.serializer(), quest.api.samples.HotSoupSeed.INSTANCE.getAnalysis()));
        cached.setTokenUsage(1000); cached.setHits(0); cached.setCreatedAt(Instant.now());
        analysisCache.save(cached);

        var lesson = lessons.findById(id).orElseThrow();
        lesson.setSourceHash(hash); lessons.save(lesson);
        var skill = new quest.server.content.Entities.SkillEntity();
        skill.setId(quest.server.analysis.StopIds.prefix8(id) + ":sk1"); skill.setLessonId(id); skill.setName("Counting on");
        skill.setSubject("english"); skill.setMethod("count on from the bigger number"); skill.setExamplesJson("[\"3 + 2\"]");
        skill.setSlideNumbersJson("[1]"); skill.setConfidence(1.0); skill.setConfirmed(true); skill.setPosition(0);
        skills.save(skill);
        return hash;
    }

    private static int attempts(JsonNode lesson, String name) {
        for (JsonNode row : lesson.get("steps")) if (name.equals(row.get("step").asText())) return row.path("attempt").asInt();
        return -1;
    }

    private String create(int day, String title) throws Exception {
        return json(mvc.perform(as(post("/teacher/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + HERS + "\",\"subject\":\"english\",\"date\":\"" + schoolDay(day)
                        + "\",\"source\":\"manual\",\"title\":\"" + title + "\"}"), teacherToken))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private static String level(String lessonId, String level) { return "/teacher/lessons/" + lessonId + "/plays/" + level + "/generate"; }

    /** Presses the button and answers the `JobRef`'s status. */
    private String generate(String id, String level, boolean replace) throws Exception {
        return json(mvc.perform(as(post(level(id, level) + (replace ? "?replace=true" : "")), teacherToken))
                .andExpect(status().isOk()).andReturn()).get("status").asText();
    }

    /** The editor's own poll, until the job is done. */
    private JsonNode awaitReview(String id) throws Exception {
        for (int i = 0; i < 150; i++) {
            var lesson = teacherLesson(id);
            String status = lesson.get("status").asText();
            if ("review".equals(status)) return lesson;
            if ("error".equals(status)) throw new AssertionError("the level failed: " + lesson.get("error"));
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for review");
    }

    private JsonNode teacherLesson(String id) throws Exception {
        return json(mvc.perform(as(get("/teacher/lessons/" + id), teacherToken)).andExpect(status().isOk()).andReturn());
    }

    /** The stop ids of one level of a lesson, as the editor sees them. */
    private List<String> stops(JsonNode lesson, int level, int variant) {
        for (JsonNode play : lesson.get("plays"))
            if (play.get("level").asInt() == level && play.get("variant").asInt() == variant) {
                var ids = new java.util.ArrayList<String>();
                play.get("play").get("stops").forEach(stop -> ids.add(stop.get("id").asText()));
                return ids;
            }
        return List.of();
    }

    private List<String> levelOne(String id) throws Exception { return stops(teacherLesson(id), 1, 0); }

    private static String step(JsonNode lesson, String name) {
        for (JsonNode row : lesson.get("steps")) if (name.equals(row.get("step").asText())) return row.get("status").asText();
        return "missing";
    }

    private void section(String id, String name, String teacherId) {
        var k = classes.findById(id).orElseGet(() -> {
            var fresh = new quest.server.tenancy.Entities.ClassEntity();
            fresh.setId(id); fresh.setSchoolId(SCHOOL); fresh.setCurriculum("british"); fresh.setGrade(1);
            fresh.setName(name); fresh.setJoinCode("AL" + Math.abs(id.hashCode() % 100000));
            fresh.setActive(true); fresh.setJoinCodeEnabled(true); fresh.setCreatedAt(Instant.now());
            return classes.save(fresh);
        });
        ClassFixtures.assign(assignments, k, "english", teacherId);
    }
}
