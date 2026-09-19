package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import quest.server.ClassFixtures;
import quest.server.admin.AdminPipelineTest;

/**
 * §8 steps 2–6 through `/teacher/**` instead of `/admin/**`: the dashboard's editor should never have to sign a
 * teacher in and call the Admin API again.
 *
 * <p>Two claims are tested. The pipeline still works when it is driven through the aliases — upload, analyse,
 * confirm the skills, edit a stop, publish — and every alias is scoped to <em>her</em> lesson, so the second teacher
 * of the same school is refused on each one. The cache is checked here too, because "Analyzed before · 0 tokens" is
 * what the teacher sees when she uploads a file a colleague has already used: the second lesson from the same PDF
 * reports `analyzedBefore` and spends nothing.
 */
class TeacherPipelineAliasTest extends TeacherTestSupport {
    private static final String SCHOOL = "tp-school";
    private static final String TEACHER = "tp-teacher", OTHER = "tp-other";
    private static final String HERS = "tp-class-1a", THEIRS = "tp-class-1b";

    @Override String prefix() { return "tp-"; }

    private String teacherToken, otherToken;

    @BeforeEach void seed() {
        school(SCHOOL, "Pipeline Academy", "TPSCH1");
        teacher(TEACHER, SCHOOL, "sara@tp.test", "Ms Sara", "[\"english\"]", "british", "[1]");
        teacher(OTHER, SCHOOL, "noor@tp.test", "Ms Noor", "[\"english\"]", "british", "[1]");
        section(HERS, "1A", TEACHER); section(THEIRS, "1B", OTHER);
        teacherToken = token(TEACHER, "TEACHER", SCHOOL);
        otherToken = token(OTHER, "TEACHER", SCHOOL);
    }

    @AfterEach void clean() {
        removeSeed();
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getClassId().startsWith(prefix())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getId().startsWith(prefix())).toList());
    }

    @Test void the_whole_pipeline_runs_through_the_teacher_routes_and_the_cache_holds() throws Exception {
        byte[] pdf = AdminPipelineTest.pdf("Ben and the Kite", "Ben has a red kite.", "The wind takes it away.");

        String first = toReview(pdf, LocalDate.now());
        var lesson = teacherLesson(first);
        assertThat(lesson.get("classId").asText()).isEqualTo(HERS);
        assertThat(lesson.get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(lesson.get("tokenUsage").asLong()).isGreaterThan(0);
        assertThat(lesson.get("analyzedBefore").asBoolean()).as("nobody had used this file before").isFalse();
        assertThat(lesson.get("plays")).hasSize(4);

        // editing a stop and publishing, both through /teacher/**
        var stop = (ObjectNode) lesson.get("plays").get(0).get("play").get("stops").get(0).deepCopy();
        stop.put("title", "Edited by the teacher");
        mvc.perform(as(put("/teacher/stops/" + stop.get("id").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(stop.toString()), teacherToken)).andExpect(status().isOk());
        var published = json(mvc.perform(as(post("/teacher/lessons/" + first + "/publish")
                .contentType(MediaType.APPLICATION_JSON).content("{\"classIds\":[\"" + HERS + "\"]}"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(published.get(0).get("version").asInt()).isEqualTo(1);

        // the same file again: the badge the editor shows, and no tokens spent
        String second = toReview(pdf, LocalDate.now().plusDays(1));
        var again = teacherLesson(second);
        assertThat(again.get("analyzedBefore").asBoolean()).isTrue();
        assertThat(again.get("files").get(0).get("cacheHit").asBoolean()).isTrue();
        assertThat(again.get("tokenUsage").asLong()).isZero();
        assertThat(again.get("tokensSaved").asLong()).isEqualTo(lesson.get("tokenUsage").asLong());
    }

    /**
     * The badge on the path that has no source files. A hand-written lesson types its text, and the first one to
     * type a given text pays for the analysis — so it must say `analyzedBefore: false`, however tempting it is to
     * infer the badge from "there are no files, so somebody else must have done the work". The second lesson with
     * the same text is the cache hit the badge is actually for.
     */
    @Test void the_badge_is_false_for_the_first_typed_lesson_and_true_for_the_second() throws Exception {
        String text = "Ben flies a red kite on a windy hill and it gets away from him.";

        String first = typed(LocalDate.now().plusDays(2), text);
        var one = teacherLesson(first);
        assertThat(one.get("source").asText()).isEqualTo("manual");
        assertThat(one.get("analyzedBefore").asBoolean()).as("the first lesson paid for this analysis").isFalse();
        assertThat(one.get("tokenUsage").asLong()).isGreaterThan(0);

        String second = typed(LocalDate.now().plusDays(3), text);
        var two = teacherLesson(second);
        assertThat(two.get("analyzedBefore").asBoolean()).isTrue();
        assertThat(two.get("tokenUsage").asLong()).isZero();
        assertThat(two.get("tokensSaved").asLong()).isEqualTo(one.get("tokenUsage").asLong());

        // and a hand-written lesson that never analysed anything claims nothing
        var blank = json(mvc.perform(as(post("/teacher/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + HERS + "\",\"subject\":\"english\",\"date\":\"" + LocalDate.now().plusDays(4)
                        + "\",\"source\":\"manual\",\"title\":\"Empty\"}"), teacherToken))
                .andExpect(status().isCreated()).andReturn());
        assertThat(blank.get("analyzedBefore").asBoolean()).isFalse();
    }

    @Test void every_alias_refuses_another_teachers_lesson() throws Exception {
        var hers = readyLesson("tp-hers", SCHOOL, HERS, "british", 1, "english", LocalDate.now());
        String stopId = stops.findByLessonId(hers.getId()).getFirst().getId();
        String playId = plays.findByLessonIdOrderByLevelAscVariantAsc(hers.getId()).getFirst().getId();

        mvc.perform(as(get("/teacher/lessons/tp-hers"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/lessons/tp-hers/analyze"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/lessons/tp-hers/retry"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/lessons/tp-hers/steps/analyze/retry"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(put("/teacher/lessons/tp-hers/skills").contentType(MediaType.APPLICATION_JSON).content("[]"), otherToken))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/lessons/tp-hers/generate-from-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"anything\"}"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(put("/teacher/lessons/tp-hers/parent-panel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectives\":{\"en\":[],\"ar\":[]},\"supported\":[],\"challenge\":[]}"), otherToken))
                .andExpect(status().isForbidden());
        mvc.perform(as(multipart("/teacher/lessons/tp-hers/files")
                .file(new MockMultipartFile("files", "s.pdf", "application/pdf", new byte[] {1})), otherToken))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/teacher/lessons/tp-hers/files"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/lessons/tp-hers/files/any/markdown"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/lessons/tp-hers/files/any/retry-conversion?method=ocr")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/lessons/tp-hers/plays").contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":2,\"variant\":0}"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/teacher/lessons/tp-hers"), otherToken)).andExpect(status().isForbidden());

        mvc.perform(as(put("/teacher/stops/" + stopId).contentType(MediaType.APPLICATION_JSON).content("{}"), otherToken))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/stops/" + stopId + "/regenerate"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/teacher/stops/" + stopId), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(put("/teacher/plays/" + playId + "/order").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopIds\":[]}"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/plays/" + playId + "/regenerate"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/plays/" + playId + "/stops").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), otherToken)).andExpect(status().isForbidden());

        // a row that does not exist is the same silence, whoever asks
        mvc.perform(as(get("/teacher/lessons/tp-nothing"), teacherToken)).andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/stops/tp-nothing/regenerate"), teacherToken)).andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/plays/tp-nothing/regenerate"), teacherToken)).andExpect(status().isNotFound());
    }

    /**
     * The two aliases N2.4a's editor was still calling on `/admin/**`: "Create level" and "Remove all files". Both
     * go through {@link quest.server.tenancy.TeacherScope#requireLesson} first, so the same call on a colleague's
     * lesson is refused above (`every_alias_refuses_another_teachers_lesson`).
     */
    @Test void create_level_and_remove_all_files_work_on_her_own_lesson() throws Exception {
        var bare = lesson("tp-bare", SCHOOL, HERS, "british", 1, "english", LocalDate.now().plusDays(5), "review");

        var play = json(mvc.perform(as(post("/teacher/lessons/" + bare.getId() + "/plays")
                .contentType(MediaType.APPLICATION_JSON).content("{\"level\":1,\"variant\":0}"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(play.get("level").asInt()).isEqualTo(1);
        assertThat(plays.findByLessonIdOrderByLevelAscVariantAsc(bare.getId())).hasSize(1);

        mvc.perform(as(delete("/teacher/lessons/" + bare.getId() + "/files"), teacherToken)).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- helpers

    private void section(String id, String name, String teacherId) {
        var k = classes.findById(id).orElseGet(() -> {
            var fresh = new quest.server.tenancy.Entities.ClassEntity();
            fresh.setId(id); fresh.setSchoolId(SCHOOL); fresh.setCurriculum("british"); fresh.setGrade(1);
            fresh.setName(name); fresh.setJoinCode("TP" + Math.abs(id.hashCode() % 100000));
            fresh.setActive(true); fresh.setJoinCodeEnabled(true); fresh.setCreatedAt(Instant.now());
            return classes.save(fresh);
        });
        ClassFixtures.assign(assignments, k, "english", teacherId);
    }

    /** Create → upload → analyse → confirm the skills, all through `/teacher/**`. */
    private String toReview(byte[] pdf, LocalDate date) throws Exception {
        String id = json(mvc.perform(as(post("/teacher/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + HERS + "\",\"subject\":\"english\",\"date\":\"" + date
                        + "\",\"source\":\"pdf\",\"notes\":\"Story: Ben and the Kite\"}"), teacherToken))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(as(multipart("/teacher/lessons/" + id + "/files")
                .file(new MockMultipartFile("files", "kite.pdf", "application/pdf", pdf)), teacherToken))
                .andExpect(status().isOk());
        mvc.perform(as(post("/teacher/lessons/" + id + "/analyze"), teacherToken)).andExpect(status().isOk());
        var waiting = await(id, "needs_review");
        var skills = mapper.createArrayNode();
        for (var s : waiting.get("skills")) skills.addObject().put("id", s.get("id").asText()).put("name", s.get("name").asText())
                .put("subject", s.get("subject").asText()).put("method", s.get("method").asText());
        mvc.perform(as(put("/teacher/lessons/" + id + "/skills").contentType(MediaType.APPLICATION_JSON)
                .content(skills.toString()), teacherToken)).andExpect(status().isOk());
        await(id, "review");
        return id;
    }

    /** A hand-written lesson whose stops are generated from typed text — the path with no source files. */
    private String typed(LocalDate date, String text) throws Exception {
        String id = json(mvc.perform(as(post("/teacher/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + HERS + "\",\"subject\":\"english\",\"date\":\"" + date
                        + "\",\"source\":\"manual\",\"title\":\"Typed\"}"), teacherToken))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(as(post("/teacher/lessons/" + id + "/generate-from-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"), teacherToken)).andExpect(status().isOk());
        await(id, "review");
        return id;
    }

    private JsonNode teacherLesson(String id) throws Exception {
        return json(mvc.perform(as(get("/teacher/lessons/" + id), teacherToken)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode await(String id, String terminal) throws Exception {
        for (int i = 0; i < 100; i++) {
            var l = teacherLesson(id);
            if (terminal.equals(l.get("status").asText())) return l;
            if ("error".equals(l.get("status").asText())) throw new AssertionError("lesson failed: " + l.get("error"));
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + terminal);
    }
}
