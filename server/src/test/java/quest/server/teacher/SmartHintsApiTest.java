package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.api.dto.Play;
import quest.server.flags.FlagKeys;
import quest.server.hints.HintRules;

/**
 * Feature 30 end to end, from the teacher's side.
 *
 * <p>Each test builds a real lesson through `POST /teacher/lessons/{id}/generate-from-text`, so the `HINTS` step runs
 * where it is meant to — last, inside the pipeline, after the panel — with the fake provider standing in for the
 * model. That provider answers the last question of every lesson <em>badly</em> on purpose
 * ({@link quest.server.analysis.SampleLlmClient}), which is how the reject-retry-drop path is exercised on every
 * build rather than only in a unit test.
 *
 * <p>The two rules the owner asked for are asserted by their own pair,
 * {@link quest.server.ai.ChildEndpointsHaveNoAiTest} and
 * {@link quest.server.children.NoAiFromChildEndpointsTest}: no child-facing route can reach a model. What is
 * asserted here is the other half — that nothing a teacher has not approved is ever in a body a child downloads.
 */
class SmartHintsApiTest extends TeacherTestSupport {
    private static final String SCHOOL = "sh-school", OTHER_SCHOOL = "sh-school-2";
    private static final String TEACHER = "sh-teacher", COLLEAGUE = "sh-colleague", OTHER = "sh-other-teacher";
    private static final String HERS = "sh-class-1a", THEIRS = "sh-class-1b", ELSEWHERE = "sh-class-2a";

    @Override String prefix() { return "sh-"; }

    private String adminToken, teacherToken, colleagueToken, otherToken;

    @Autowired quest.server.hints.StopHintsRepository hintRows;
    @Autowired quest.server.hints.PracticePlayRepository practiceRows;
    @Autowired quest.server.content.LessonStepRepository stepRows;
    @Autowired quest.server.content.LessonStore store;

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Smart Hints Academy", "SHSCH1");
        school(OTHER_SCHOOL, "The Other School", "SHSCH2");
        teacher(TEACHER, SCHOOL, TEACHER + "@seed.test", "Ms Hana", "[\"english\"]", "british", "[1]");
        teacher(COLLEAGUE, SCHOOL, COLLEAGUE + "@seed.test", "Ms Noor", "[\"english\"]", "british", "[1]");
        teacher(OTHER, OTHER_SCHOOL, OTHER + "@seed.test", "Ms Dana", "[\"english\"]", "british", "[1]");
        klass(HERS, SCHOOL, "british", 1, "english", TEACHER);
        klass(THEIRS, SCHOOL, "british", 1, "english", COLLEAGUE);
        klass(ELSEWHERE, OTHER_SCHOOL, "british", 1, "english", OTHER);
        adminToken = adminToken();
        teacherToken = token(TEACHER, "TEACHER", SCHOOL);
        colleagueToken = token(COLLEAGUE, "TEACHER", SCHOOL);
        otherToken = token(OTHER, "TEACHER", OTHER_SCHOOL);
        setFlag(adminToken, SCHOOL, FlagKeys.SMART_HINTS, true);
        setFlag(adminToken, OTHER_SCHOOL, FlagKeys.SMART_HINTS, true);
    }

    @AfterEach void clean() throws Exception {
        setFlag(adminToken, SCHOOL, FlagKeys.SMART_HINTS, false);
        setFlag(adminToken, OTHER_SCHOOL, FlagKeys.SMART_HINTS, false);
        removeSeed();
        // the step ledger of a lesson that has gone; the sections and their assignments stay, because the children
        // this test seeded point at them and @BeforeEach reuses them as they are
        stepRows.deleteAll(stepRows.findAll().stream().filter(r -> lessons.findById(r.getLessonId()).isEmpty()).toList());
    }

    /**
     * The whole of it on one lesson: the step writes hints and practice sets that nobody has approved, publishing
     * carries none of them, and the next version carries exactly what she signed.
     */
    @Test void the_build_writes_unapproved_work_and_only_what_she_approves_is_published() throws Exception {
        String id = built(0);

        var lesson = teacherLesson(id);
        assertThat(step(lesson, "hints")).as("the step runs at build time, after the panel").isEqualTo("done");

        var view = view(id);
        int answerable = answerableStops(id);
        int written = view.get("hints").size();
        assertThat(written).as("one entry per answerable stop, less the ones the model would not fix").isBetween(1, answerable - 1);
        assertThat(view.get("hints")).allSatisfy(row -> {
            assertThat(row.get("approved").asBoolean()).as("nothing is approved by being written").isFalse();
            assertThat(row.get("hints")).hasSize(HintRules.COUNT);
            row.get("hints").forEach(hint -> assertThat(hint.asText().length()).isLessThanOrEqualTo(HintRules.MAX_CHARS));
        });
        assertThat(view.get("practice")).isNotEmpty();
        assertThat(view.get("practice")).allSatisfy(row -> {
            assertThat(row.get("approved").asBoolean()).isFalse();
            assertThat(row.get("variant").asInt()).isGreaterThanOrEqualTo(Play.PRACTICE_VARIANT);
            assertThat(row.get("play").get("stops").size()).isBetween(4, 5);
        });

        // published with nothing approved: the body a child downloads has neither
        publish(id);
        var first = parentGet("/lessons/" + id);
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("smartHints")).as("an unapproved hint is a draft on her screen").isEmpty();
        assertThat(first.get("practice")).isEmpty();

        // she approves everything, and one practice set
        String stopId = view.get("hints").get(0).get("stopId").asText();
        String playId = view.get("practice").get(0).get("playId").asText();
        mvc.perform(as(post("/teacher/lessons/" + id + "/smart-hints/approve").contentType(MediaType.APPLICATION_JSON).content("{}"), teacherToken)).andExpect(status().isOk());
        mvc.perform(as(post("/teacher/lessons/" + id + "/practice/" + playId + "/approve"), teacherToken)).andExpect(status().isOk());
        assertThat(view(id).get("hints")).allSatisfy(row -> assertThat(row.get("approved").asBoolean()).isTrue());

        mvc.perform(as(post("/teacher/lessons/" + id + "/unpublish"), teacherToken)).andExpect(status().isOk());
        publish(id);
        var second = parentGet("/lessons/" + id);
        assertThat(second.get("version").asInt()).as("approved content arrives at the next version").isEqualTo(2);
        assertThat(second.get("smartHints").size()).isEqualTo(written);
        assertThat(second.get("smartHints").get(stopId)).hasSize(HintRules.COUNT);
        assertThat(second.get("practice")).as("only the one she approved").hasSize(1);
        assertThat(second.get("practice").get(0).get("play").get("variant").asInt()).isGreaterThanOrEqualTo(Play.PRACTICE_VARIANT);
        assertThat(second.get("practice").get(0).get("skillId").asText()).isNotBlank();
        // the Again variant is still the Again variant and not one of the practice sets
        assertThat(second.get("variant").get("variant").asInt()).isEqualTo(1);
        assertThat(second.get("plays")).hasSize(3);
    }

    /** The one hint that gives the answer away: refused from the model, and refused from her too. */
    @Test void a_hint_that_gives_the_answer_away_is_dropped_and_cannot_be_saved() throws Exception {
        String id = built(1);
        var view = view(id);
        var row = view.get("hints").get(0);
        String stopId = row.get("stopId").asText();
        String answer = row.get("answer").asText().split(", ")[0];
        assertThat(answer).isNotBlank();

        var refused = json(mvc.perform(as(put("/teacher/lessons/" + id + "/smart-hints/" + stopId)
                .contentType(MediaType.APPLICATION_JSON).content(hints("Look again.", "It is " + answer + ".")), teacherToken))
                .andExpect(status().isBadRequest()).andReturn());
        assertThat(refused.get("message").asText()).contains("gives the answer away");

        // and the stop the model would not fix has no hints at all rather than bad ones
        var withHints = new ArrayList<String>();
        view.get("hints").forEach(h -> withHints.add(h.get("stopId").asText()));
        assertThat(withHints.size()).as("the model's leaking hints cost those stops their hints and nothing else")
                .isBetween(1, answerableStops(id) - 1);
        assertThat(hintRows.findByLessonId(id)).hasSize(withHints.size());
        for (JsonNode kept : view.get("hints"))
            for (JsonNode hint : kept.get("hints"))
                assertThat(HintRules.leak(hint.asText(), List.of(kept.get("answer").asText().split(", ")))).isNull();
    }

    /** `approvedAt` means "a teacher read exactly these words", so her own edit has to clear it. */
    @Test void editing_a_hint_clears_the_approval() throws Exception {
        String id = built(2);
        String stopId = view(id).get("hints").get(0).get("stopId").asText();
        mvc.perform(as(post("/teacher/lessons/" + id + "/smart-hints/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopIds\":[\"" + stopId + "\"]}"), teacherToken)).andExpect(status().isOk());
        assertThat(hintRow(id, stopId).get("approved").asBoolean()).isTrue();

        var saved = json(mvc.perform(as(put("/teacher/lessons/" + id + "/smart-hints/" + stopId)
                .contentType(MediaType.APPLICATION_JSON).content(hints("Count them out loud first.", "Look at the picture again.")), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(saved.get("approved").asBoolean()).isFalse();
        assertThat(saved.get("hints").get(0).asText()).isEqualTo("Count them out loud first.");
        assertThat(hintRow(id, stopId).get("approved").asBoolean()).isFalse();

        // and an edit that is not two hints is refused outright
        mvc.perform(as(put("/teacher/lessons/" + id + "/smart-hints/" + stopId)
                .contentType(MediaType.APPLICATION_JSON).content(hints("Only one.")), teacherToken)).andExpect(status().isBadRequest());
    }

    /** §8: an exam has no help on screen, so it gets neither hints nor practice however the school's switch stands. */
    @Test void an_exam_gets_no_hints_and_no_practice() throws Exception {
        String id = create(3, "The end-of-unit test");
        var lesson = lessons.findById(id).orElseThrow();
        lesson.setType("exam"); lessons.save(lesson);
        fromText(id);
        awaitReview(id);

        assertThat(step(teacherLesson(id), "hints")).as("the step is done, having had nothing to do").isEqualTo("done");
        assertThat(hintRows.findByLessonId(id)).isEmpty();
        assertThat(practiceRows.findByLessonIdOrderBySkillIdAsc(id)).isEmpty();
        assertThat(view(id).get("hints")).isEmpty();
    }

    /**
     * The map's review island for a weak skill: that skill's approved practice set when there is one, and the
     * generic "Again" play when there is not.
     */
    @Test void the_review_island_uses_the_approved_practice_and_falls_back_to_again() throws Exception {
        var section = klass(HERS, SCHOOL, "british", 1, "english", TEACHER);
        String lessonId = prefix() + "weak-lesson";
        lessonWithSkill(lessonId, SCHOOL, section.getId(), "british", 1, "english", java.time.LocalDate.now(), "Counting on");
        String child = child("Lina", "SHSCH1", section);
        for (int i = 0; i < 3; i++) attempt(child, lessonId, stopId(lessonId), false, Instant.now().minusSeconds(600));

        String fallback = island(child).get("playId").asText();
        assertThat(fallback).as("today's behaviour without an approved practice set").isEqualTo(lessonId + ":1:1");

        // one practice set for that very skill, approved
        var play = store.savePlay(lessonId, new Play(1, Play.PRACTICE_VARIANT, quest.api.dto.SourceKind.MATH,
                new quest.api.dto.Theme("Pot", "Soup", "🍲", "Served!"),
                List.of(choice(lessonId + ":1:2:p1", "Pick one")), null), "v1", 0);
        var row = new quest.server.hints.Entities.PracticePlayEntity();
        row.setPlayId(play.getId()); row.setLessonId(lessonId); row.setSchoolId(SCHOOL); row.setSkillId(lessonId + ":skill-1");
        row.setApprovedBy(TEACHER); row.setApprovedAt(Instant.now()); row.setCreatedAt(Instant.now());
        practiceRows.save(row);

        assertThat(island(child).get("playId").asText()).isEqualTo(lessonId + ":1:" + Play.PRACTICE_VARIANT);

        // …and the island falls back again the moment she takes the approval away
        row.setApprovedBy(null); row.setApprovedAt(null); practiceRows.save(row);
        assertThat(island(child).get("playId").asText()).isEqualTo(lessonId + ":1:1");
    }

    /**
     * A lesson that is not hers, on every route of the feature — refused by {@link quest.server.tenancy.TeacherScope}
     * before anything is read, with the two answers the rest of `/teacher/lessons/{id}/**` already gives: 403 to a
     * colleague of her own school ("that lesson is not one of yours") and 404 to another school's teacher, for whom
     * the tenant filter means the lesson does not exist at all.
     */
    @Test void a_lesson_that_is_not_hers_is_refused_on_every_route() throws Exception {
        String id = built(4);
        for (var refused : java.util.Map.of(colleagueToken, 403, otherToken, 404).entrySet()) {
            String token = refused.getKey();
            int expected = refused.getValue();
            for (var request : List.of(
                    as(get("/teacher/lessons/" + id + "/smart-hints"), token),
                    as(put("/teacher/lessons/" + id + "/smart-hints/whatever").contentType(MediaType.APPLICATION_JSON).content(hints("One.", "Two.")), token),
                    as(post("/teacher/lessons/" + id + "/smart-hints/approve").contentType(MediaType.APPLICATION_JSON).content("{}"), token),
                    as(post("/teacher/lessons/" + id + "/practice/whatever/approve"), token),
                    as(post("/teacher/lessons/" + id + "/practice/whatever/reject"), token)))
                assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(expected);
        }
        // and her own rows are untouched by any of it
        assertThat(hintRows.findByLessonId(id)).isNotEmpty();
    }

    /** The switch off: the step writes nothing and every route answers as if the feature had never been built. */
    @Test void with_the_flag_off_nothing_is_written_and_the_routes_are_hidden() throws Exception {
        setFlag(adminToken, SCHOOL, FlagKeys.SMART_HINTS, false);
        String id = built(5);

        assertThat(step(teacherLesson(id), "hints")).isEqualTo("done");
        assertThat(hintRows.findByLessonId(id)).isEmpty();
        assertThat(practiceRows.findByLessonIdOrderBySkillIdAsc(id)).isEmpty();
        assertThat(store.plays(id)).as("no practice variant was written either").allMatch(p -> p.getVariant() <= 1);

        mvc.perform(as(get("/teacher/lessons/" + id + "/smart-hints"), teacherToken)).andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/lessons/" + id + "/smart-hints/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), teacherToken)).andExpect(status().isNotFound());

        publish(id);
        var published = parentGet("/lessons/" + id);
        assertThat(published.get("smartHints")).isEmpty();
        assertThat(published.get("practice")).isEmpty();
    }

    /** She rejects a practice set: it and its play go, and the next publish cannot carry it. */
    @Test void a_rejected_practice_set_and_its_play_are_gone() throws Exception {
        String id = built(6);
        var practice = view(id).get("practice");
        assertThat(practice).isNotEmpty();
        String playId = practice.get(0).get("playId").asText();
        int before = store.plays(id).size();

        mvc.perform(as(post("/teacher/lessons/" + id + "/practice/" + playId + "/reject"), teacherToken)).andExpect(status().isNoContent());

        assertThat(store.plays(id)).hasSize(before - 1);
        assertThat(practiceRows.findById(playId)).isEmpty();
        assertThat(view(id).get("practice")).noneSatisfy(row -> assertThat(row.get("playId").asText()).isEqualTo(playId));
        mvc.perform(as(post("/teacher/lessons/" + id + "/practice/" + playId + "/approve"), teacherToken)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    /** A lesson built the way a teacher builds one from typed text: the whole pipeline, `HINTS` included. */
    private String built(int day) throws Exception {
        String id = create(day, "Hot soup for mummy");
        fromText(id);
        awaitReview(id);
        return id;
    }

    private String create(int day, String title) throws Exception {
        return json(mvc.perform(as(post("/teacher/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + HERS + "\",\"subject\":\"english\",\"date\":\"" + schoolDay(day)
                        + "\",\"source\":\"manual\",\"title\":\"" + title + "\"}"), teacherToken))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private void fromText(String id) throws Exception {
        mvc.perform(as(post("/teacher/lessons/" + id + "/generate-from-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Mummy is in bed with a cold. Alan and Daddy make her hot soup and she feels better.\"}"), teacherToken))
                .andExpect(status().isOk());
    }

    private void publish(String id) throws Exception {
        mvc.perform(as(post("/teacher/lessons/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classIds\":[\"" + HERS + "\"]}"), teacherToken)).andExpect(status().isOk());
    }

    private JsonNode view(String id) throws Exception {
        return json(mvc.perform(as(get("/teacher/lessons/" + id + "/smart-hints"), teacherToken)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode hintRow(String id, String stopId) throws Exception {
        for (JsonNode row : view(id).get("hints")) if (stopId.equals(row.get("stopId").asText())) return row;
        throw new AssertionError("no hints for " + stopId);
    }

    /** The review island of the child's map, which is the first island on a day that has a lesson. */
    private JsonNode island(String childId) throws Exception {
        var today = java.time.LocalDate.now();
        var map = parentGet("/children/" + childId + "/map?from=" + today.minusDays(7) + "&to=" + today.plusDays(7) + "&today=" + today);
        for (JsonNode i : map.get("islands")) if ("review".equals(i.get("kind").asText())) return i;
        throw new AssertionError("no review island in " + map.get("islands"));
    }

    /** How many stops of the lesson's levels and Again variant a child answers — and therefore can be hinted at. */
    private int answerableStops(String id) {
        int n = 0;
        for (var entity : store.plays(id)) if (entity.getVariant() <= 1) n += HintRules.answerableStops(store.play(entity)).size();
        return n;
    }

    private String hints(String... values) {
        var out = new ArrayList<String>();
        for (String v : values) out.add("\"" + v + "\"");
        return "{\"hints\":[" + String.join(",", out) + "]}";
    }

    private JsonNode teacherLesson(String id) throws Exception {
        return json(mvc.perform(as(get("/teacher/lessons/" + id), teacherToken)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode awaitReview(String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var lesson = teacherLesson(id);
            String status = lesson.get("status").asText();
            if ("review".equals(status)) return lesson;
            if ("error".equals(status)) throw new AssertionError("the build failed: " + lesson.get("error"));
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for review");
    }

    private static String step(JsonNode lesson, String name) {
        for (JsonNode row : lesson.get("steps")) if (name.equals(row.get("step").asText())) return row.get("status").asText();
        return "missing";
    }
}
