package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import quest.server.ClassFixtures;
import quest.server.flags.FlagKeys;

/**
 * `docs/teacher-flow.md` §8 — what a teacher may do with a lesson, and what she may not.
 *
 * <p>The copy is the one worth reading twice. §4's drag-to-copy only makes sense if 1B's copy is a lesson of its own:
 * the same stop id in both classes would file 1B's answers into 1A's results, which is the kind of bug that is
 * invisible until a parent-teacher meeting. {@link #a_copy_has_results_of_its_own} plays the copy and asserts the
 * source's numbers did not move.
 */
class TeacherLessonsTest extends TeacherTestSupport {
    private static final String SCHOOL = "tl-school";
    private static final String TEACHER = "tl-teacher";
    private static final String OTHER = "tl-other-teacher";
    private static final String A = "tl-class-1a", B = "tl-class-1b", C = "tl-class-2a", HERS_NOT = "tl-class-1c";

    @Override String prefix() { return "tl-"; }

    private String teacherToken, otherToken, adminToken;

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Lesson Academy", "TLSCH1");
        teacher(TEACHER, SCHOOL, "sara@tl.test", "Ms Sara", "[\"math\"]", "british", "[1,2]");
        teacher(OTHER, SCHOOL, "noor@tl.test", "Ms Noor", "[\"math\"]", "british", "[1]");
        section(A, "1A", 1, TEACHER); section(B, "1B", 1, TEACHER); section(C, "2A", 2, TEACHER);
        section(HERS_NOT, "1C", 1, OTHER);
        teacherToken = token(TEACHER, "TEACHER", SCHOOL);
        otherToken = token(OTHER, "TEACHER", SCHOOL);
        adminToken = adminToken();
    }

    @AfterEach void clean() {
        removeSeed();
        childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith(prefix()) && c.getClassId() != null)
                .forEach(c -> { c.setClassId(null); childRows.save(c); });
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getClassId().startsWith(prefix())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getId().startsWith(prefix())).toList());
    }

    // ---------------------------------------------------------------- create (§8 step 1)

    @Test void a_lesson_is_created_into_one_of_her_assignments_and_nowhere_else() throws Exception {
        var made = create(teacherToken, A, "math", LocalDate.now(), status().isCreated());
        assertThat(made.get("classId").asText()).isEqualTo(A);
        assertThat(made.get("className").asText()).isEqualTo("1A");
        assertThat(made.get("teacherId").asText()).isEqualTo(TEACHER);
        assertThat(made.get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(made.get("type").asText()).isEqualTo("homework");
        assertThat(made.get("status").asText()).isEqualTo("draft");

        create(teacherToken, HERS_NOT, "math", LocalDate.now(), status().isForbidden());
        create(teacherToken, A, "english", LocalDate.now(), status().isForbidden());
    }

    /** §4 of the schools prompt: a source her school has switched off is the same silence an unknown route gives. */
    @Test void a_source_the_school_has_switched_off_is_not_accepted() throws Exception {
        setFlag(adminToken, SCHOOL, FlagKeys.LESSONS_PDF, false);
        try { create(teacherToken, A, "math", LocalDate.now(), status().isNotFound()); }
        finally { setFlag(adminToken, SCHOOL, FlagKeys.LESSONS_PDF, true); }
    }

    // ---------------------------------------------------------------- move

    @Test void a_lesson_moves_day_while_it_is_unpublished_and_not_after() throws Exception {
        var made = create(teacherToken, A, "math", LocalDate.now(), status().isCreated());
        String id = made.get("id").asText();
        var moved = json(mvc.perform(as(patch("/teacher/lessons/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + LocalDate.now().plusDays(2) + "\"}"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(moved.get("date").asText()).isEqualTo(LocalDate.now().plusDays(2).toString());

        publishReady("tl-published", A);
        mvc.perform(as(patch("/teacher/lessons/tl-published").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + LocalDate.now().plusDays(1) + "\"}"), teacherToken))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- copy

    @Test void a_copy_has_results_of_its_own() throws Exception {
        var source = readyLesson("tl-source", SCHOOL, A, "british", 1, "math", LocalDate.now());
        var copy = json(mvc.perform(as(post("/teacher/lessons/" + source.getId() + "/copy")
                .contentType(MediaType.APPLICATION_JSON).content("{\"classId\":\"" + B + "\"}"), teacherToken))
                .andExpect(status().isCreated()).andReturn());

        String copyId = copy.get("id").asText();
        assertThat(copyId).isNotEqualTo(source.getId());
        assertThat(copy.get("classId").asText()).isEqualTo(B);
        assertThat(copy.get("status").asText()).isEqualTo("review");
        assertThat(copy.get("analyzedBefore").asBoolean()).as("the copy keeps the source's file hash").isTrue();
        assertThat(copy.get("tokenUsage").asLong()).isZero();
        assertThat(copy.get("plays")).hasSize(4);
        assertThat(copy.get("skills")).hasSize(1);

        // every id the copy owns is its own
        var copiedStop = copy.get("plays").get(0).get("play").get("stops").get(0).get("id").asText();
        assertThat(copiedStop).startsWith(copyId.substring(0, 8) + ":").doesNotContain(source.getId().substring(0, 8));
        assertThat(copy.get("skills").get(0).get("id").asText()).startsWith(copyId.substring(0, 8) + ":");
        assertThat(stops.findByLessonId(copyId)).hasSize(4);

        // and an answer on one class's copy is invisible in the other's results
        var child = child("Layla", "TLSCH1", "british", 1);
        attempt(child, copyId, copiedStop, true, Instant.now());
        assertThat(attempts.findByLessonId(copyId)).hasSize(1);
        assertThat(attempts.findByLessonId(source.getId())).as("the source's results did not move").isEmpty();
    }

    @Test void a_copy_only_goes_to_a_sibling_class_she_teaches() throws Exception {
        readyLesson("tl-src-2", SCHOOL, A, "british", 1, "math", LocalDate.now());
        copy("tl-src-2", HERS_NOT, teacherToken).andExpect(status().isForbidden());
        copy("tl-src-2", C, teacherToken).andExpect(status().isForbidden());     // her class, but grade 2
        copy("tl-src-2", A, teacherToken).andExpect(status().isBadRequest());    // already there
        copy("tl-src-2", B, otherToken).andExpect(status().isForbidden());       // not her lesson
    }

    // ---------------------------------------------------------------- publish

    @Test void publishing_to_two_classes_makes_one_copy_each_with_its_own_version() throws Exception {
        readyLesson("tl-multi", SCHOOL, A, "british", 1, "math", LocalDate.now());
        var results = publish("tl-multi", "[\"" + A + "\",\"" + B + "\"]");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("classId").asText()).isEqualTo(A);
        assertThat(results.get(0).get("lessonId").asText()).isEqualTo("tl-multi");
        assertThat(results.get(0).get("version").asInt()).isEqualTo(1);
        String copyId = results.get(1).get("lessonId").asText();
        assertThat(copyId).isNotEqualTo("tl-multi");
        assertThat(results.get(1).get("version").asInt()).isEqualTo(1);
        assertThat(lessons.findOneById(copyId).orElseThrow().getStatus()).isEqualTo("published");

        // re-publishing bumps the version in each class rather than leaving a second card behind
        var again = publish("tl-multi", "[\"" + A + "\",\"" + B + "\"]");
        assertThat(again.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(again.get(1).get("lessonId").asText()).as("the same copy, not a second one").isEqualTo(copyId);
        assertThat(again.get(1).get("version").asInt()).isEqualTo(2);
    }

    /**
     * The review's case. 1B is already holding a draft of its own, on the same day and in the same subject — a
     * lesson the other half of a shared class is working on, or one this teacher wrote and has not finished.
     * Publishing 1A's lesson to 1B must copy, not reach across and flip that draft live.
     */
    @Test void publishing_to_a_class_that_holds_an_unrelated_lesson_copies_and_leaves_it_alone() throws Exception {
        var day = LocalDate.now();
        readyLesson("tl-mine", SCHOOL, A, "british", 1, "math", day);
        var stranger = lesson("tl-stranger", SCHOOL, B, "british", 1, "math", day, "draft");

        var results = publish("tl-mine", "[\"" + B + "\"]");

        assertThat(results).hasSize(1);
        String copyId = results.get(0).get("lessonId").asText();
        assertThat(copyId).isNotEqualTo("tl-stranger").isNotEqualTo("tl-mine");
        assertThat(lessons.findOneById(copyId).orElseThrow().getCopiedFromLessonId()).isEqualTo("tl-mine");
        assertThat(lessons.findOneById("tl-stranger").orElseThrow().getStatus())
                .as("somebody else's draft is not this teacher's to publish").isEqualTo("draft");
        assertThat(stranger.getVersion()).isEqualTo(lessons.findOneById("tl-stranger").orElseThrow().getVersion());

        // and the second publish still finds its own copy rather than making another or taking the stranger
        var again = publish("tl-mine", "[\"" + B + "\"]");
        assertThat(again.get(0).get("lessonId").asText()).isEqualTo(copyId);
        assertThat(again.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(lessons.findOneById("tl-stranger").orElseThrow().getStatus()).isEqualTo("draft");
    }

    @Test void unpublishing_takes_it_back_to_ready_and_the_next_publish_bumps_the_version() throws Exception {
        readyLesson("tl-version", SCHOOL, A, "british", 1, "math", LocalDate.now());
        assertThat(publish("tl-version", "[\"" + A + "\"]").get(0).get("version").asInt()).isEqualTo(1);

        var back = json(mvc.perform(as(post("/teacher/lessons/tl-version/unpublish"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(back.get("status").asText()).isEqualTo("review");
        assertThat(publish("tl-version", "[\"" + A + "\"]").get(0).get("version").asInt()).isEqualTo(2);
    }

    @Test void a_teacher_cannot_publish_or_read_another_teachers_lesson() throws Exception {
        readyLesson("tl-hers", SCHOOL, A, "british", 1, "math", LocalDate.now());
        mvc.perform(as(get("/teacher/lessons/tl-hers"), otherToken)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/lessons/tl-hers/publish").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classIds\":[\"" + HERS_NOT + "\"]}"), otherToken)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- delete

    @Test void only_a_draft_or_a_failed_lesson_can_be_deleted() throws Exception {
        String draft = create(teacherToken, A, "math", LocalDate.now(), status().isCreated()).get("id").asText();
        mvc.perform(as(delete("/teacher/lessons/" + draft), teacherToken)).andExpect(status().isNoContent());

        readyLesson("tl-ready", SCHOOL, A, "british", 1, "math", LocalDate.now());
        mvc.perform(as(delete("/teacher/lessons/tl-ready"), teacherToken)).andExpect(status().isConflict());
        publish("tl-ready", "[\"" + A + "\"]");
        mvc.perform(as(delete("/teacher/lessons/tl-ready"), teacherToken)).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- helpers

    private void section(String id, String name, int grade, String teacherId) {
        var existing = classes.findById(id);
        var k = existing.orElseGet(() -> {
            var fresh = new quest.server.tenancy.Entities.ClassEntity();
            fresh.setId(id); fresh.setSchoolId(SCHOOL); fresh.setCurriculum("british"); fresh.setGrade(grade);
            fresh.setName(name); fresh.setJoinCode("TL" + Math.abs(id.hashCode() % 100000));
            fresh.setActive(true); fresh.setJoinCodeEnabled(true); fresh.setCreatedAt(Instant.now());
            return classes.save(fresh);
        });
        ClassFixtures.assign(assignments, k, "math", teacherId);
    }

    private JsonNode create(String token, String classId, String subject, LocalDate date, ResultMatcher expected) throws Exception {
        return json(mvc.perform(as(post("/teacher/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + classId + "\",\"subject\":\"" + subject + "\",\"date\":\"" + date
                        + "\",\"source\":\"pdf\",\"title\":\"Counting to ten\"}"), token)).andExpect(expected).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions copy(String id, String classId, String token) throws Exception {
        return mvc.perform(as(post("/teacher/lessons/" + id + "/copy").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + classId + "\"}"), token));
    }

    private JsonNode publish(String id, String classIds) throws Exception {
        return json(mvc.perform(as(post("/teacher/lessons/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classIds\":" + classIds + "}"), teacherToken)).andExpect(status().isOk()).andReturn());
    }

    private void publishReady(String id, String classId) throws Exception {
        readyLesson(id, SCHOOL, classId, "british", 1, "math", LocalDate.now());
        publish(id, "[\"" + classId + "\"]");
    }
}
