package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * §6 screen 15, "My students": the class list with stars, level and weak skills, and one child's timeline with the
 * retells and drawings she saved.
 */
class TeacherStudentsTest extends TeacherTestSupport {
    private static final String A = "ts-school-a", B = "ts-school-b";
    private static final String TEACHER_A = "ts-teacher-a", TEACHER_A2 = "ts-teacher-a2", MANAGER_A = "ts-manager-a", TEACHER_B = "ts-teacher-b";
    private static final String CLASS_A1 = "ts-school-a:british:1:math", CLASS_OTHER = "ts-school-a:british:3:english";
    private static final String CLASS_B1 = "ts-school-b:british:1:math";
    private static final String LESSON = "ts-lesson-1";

    @Override String prefix() { return "ts-"; }

    private String teacherToken, otherTeacherToken, managerToken, teacherBToken, adminToken;
    private String maya;

    @BeforeEach void seed() throws Exception {
        school(A, "Student Academy", "TSSCHA");
        school(B, "Student Beta", "TSSCHB");
        teacher(TEACHER_A, A, "a@ts.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        teacher(TEACHER_A2, A, "a2@ts.test", "Ms Dana", "[\"english\"]", "british", "[3]");
        teacher(TEACHER_B, B, "b@ts.test", "Ms Lina", "[\"math\"]", "british", "[1]");
        user(MANAGER_A, A, "m@ts.test", "MANAGERIAL");
        klass(CLASS_A1, A, "british", 1, "math", TEACHER_A);
        klass(CLASS_OTHER, A, "british", 3, "english", TEACHER_A2);
        klass(CLASS_B1, B, "british", 1, "math", TEACHER_B);
        lessonWithSkill(LESSON, A, CLASS_A1, "british", 1, "math", LocalDate.now().minusDays(2), "Counting to ten");

        adminToken = adminToken();
        teacherToken = token(TEACHER_A, "TEACHER", A);
        otherTeacherToken = token(TEACHER_A2, "TEACHER", A);
        managerToken = token(MANAGER_A, "MANAGERIAL", A);
        teacherBToken = token(TEACHER_B, "TEACHER", B);

        maya = child("Maya", "TSSCHA", "british", 1);
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void the_class_list_shows_stars_level_and_weak_skills() throws Exception {
        // three wrong first tries on the single-answer stop: a NEEDS_ANOTHER_LOOK band
        for (int i = 1; i <= 3; i++)
            attempt(maya, LESSON, stopId(LESSON), false, Instant.now().minus(i, ChronoUnit.HOURS));

        var rows = json(mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("childId").asText()).isEqualTo(maya);
        assertThat(row.get("name").asText()).isEqualTo("Maya");
        assertThat(row.get("starsThisWeek").asInt()).as("best per stop, not a sum over every attempt").isEqualTo(1);
        assertThat(row.get("levelReached").asInt()).as("nothing completed yet").isZero();
        assertThat(row.get("lastPlayed").isNull()).isFalse();
        assertThat(row.get("weakSkills")).hasSize(1);
        assertThat(row.get("weakSkills").get(0).get("name").asText()).isEqualTo("Counting to ten");
        assertThat(row.get("weakSkills").get(0).get("band").asText()).isEqualTo("NEEDS_ANOTHER_LOOK");
    }

    @Test void a_child_who_has_played_nothing_still_appears_with_zeros() throws Exception {
        var rows = json(mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("starsThisWeek").asInt()).isZero();
        assertThat(rows.get(0).get("weakSkills")).isEmpty();
        assertThat(rows.get(0).get("lastPlayed").isNull()).isTrue();
    }

    @Test void the_timeline_shows_what_she_played_and_what_she_saved() throws Exception {
        attempt(maya, LESSON, stopId(LESSON), true, Instant.now().minus(3, ChronoUnit.HOURS));

        // a recording, uploaded the way the app uploads one
        var upload = json(mvc.perform(multipart("/children/" + maya + "/stops/" + stopId(LESSON) + "/media")
                .file(new MockMultipartFile("file", "retell.m4a", "audio/mp4", new byte[] {1, 2, 3}))
                .param("kind", "recording").header("Authorization", PARENT)).andExpect(status().is2xxSuccessful()).andReturn());
        String mediaId = upload.get("id").asText();

        var timeline = json(mvc.perform(as(get("/teacher/students/" + maya + "/timeline"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(timeline.get("childId").asText()).isEqualTo(maya);
        assertThat(timeline.get("name").asText()).isEqualTo("Maya");
        assertThat(timeline.get("media")).hasSize(1);
        assertThat(timeline.get("media").get(0).get("id").asText()).isEqualTo(mediaId);
        assertThat(timeline.get("media").get(0).get("kind").asText()).isEqualTo("recording");
        assertThat(timeline.get("media").get(0).get("url").asText()).endsWith("/media/child/" + mediaId);

        // …and that URL really is readable by a teacher of the child's school (`MediaAccess` already allowed it)
        mvc.perform(as(get("/media/child/" + mediaId), teacherToken)).andExpect(status().isOk());
        mvc.perform(as(get("/media/child/" + mediaId), managerToken)).andExpect(status().isOk());
        mvc.perform(as(get("/media/child/" + mediaId), adminToken)).andExpect(status().isOk());
        // a teacher of another school gets the same 404 an unknown id gets — never a 403 that would confirm it
        mvc.perform(as(get("/media/child/" + mediaId), teacherBToken)).andExpect(status().isNotFound());
    }

    @Test void a_completed_lesson_and_an_answered_question_are_both_on_the_timeline() throws Exception {
        // the one stop of the seeded lesson, answered — which derives the level-1 completion
        String attemptId = "ts-att-" + java.util.UUID.randomUUID();
        parentPost("/children/" + maya + "/attempts", "[{\"id\":\"" + attemptId + "\",\"stopId\":\"" + stopId(LESSON)
                + "\",\"lessonId\":\"" + LESSON + "\",\"level\":1,\"answerJson\":\"{}\",\"correct\":true,"
                + "\"attemptNumber\":1,\"mistakes\":0,\"stars\":3,\"answeredAt\":" + System.currentTimeMillis() + "}]");

        var timeline = json(mvc.perform(as(get("/teacher/students/" + maya + "/timeline"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        var kinds = new java.util.ArrayList<String>();
        timeline.get("entries").forEach(e -> kinds.add(e.get("kind").asText()));
        assertThat(kinds).contains("lesson.completed");

        var rows = json(mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(rows.get(0).get("levelReached").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("starsThisWeek").asInt()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- who may look

    @Test void a_teacher_reaches_only_her_own_classes_and_children() throws Exception {
        mvc.perform(as(get("/teacher/classes/" + CLASS_OTHER + "/students"), teacherToken)).andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), otherTeacherToken)).andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/students/" + maya + "/timeline"), otherTeacherToken)).andExpect(status().isForbidden());
    }

    @Test void a_managerial_user_reads_her_whole_school_and_an_admin_any() throws Exception {
        mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), managerToken)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/classes/" + CLASS_OTHER + "/students"), managerToken)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/students/" + maya + "/timeline"), managerToken)).andExpect(status().isOk());
        mvc.perform(scoped(get("/teacher/classes/" + CLASS_A1 + "/students"), adminToken, A)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/students/" + maya + "/timeline"), adminToken)).andExpect(status().isOk());
    }

    @Test void another_schools_class_and_child_are_not_found() throws Exception {
        mvc.perform(as(get("/teacher/classes/" + CLASS_B1 + "/students"), teacherToken)).andExpect(status().isNotFound());
        mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), teacherBToken)).andExpect(status().isNotFound());
        mvc.perform(as(get("/teacher/students/" + maya + "/timeline"), teacherBToken)).andExpect(status().isNotFound());
    }

    @Test void the_timeline_window_is_validated() throws Exception {
        mvc.perform(as(get("/teacher/students/" + maya + "/timeline?from=not-a-date"), teacherToken)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/teacher/students/" + maya + "/timeline?from=2027-05-01&to=2027-04-01"), teacherToken)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/teacher/students/" + maya + "/timeline?from=2020-01-01&to=2027-04-01"), teacherToken)).andExpect(status().isBadRequest());
    }
}
