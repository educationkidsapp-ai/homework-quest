package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** §6 screen 16: a note to the parents of one class, live in the app's parent mode until it expires or is removed. */
class AnnouncementTest extends TeacherTestSupport {
    private static final String A = "an-school-a";
    private static final String TEACHER_A = "an-teacher-a", TEACHER_A2 = "an-teacher-a2", MANAGER_A = "an-manager-a";
    private static final String CLASS_A1 = "an-school-a:british:1:math", CLASS_OTHER = "an-school-a:british:3:english";

    @Override String prefix() { return "an-"; }

    private String adminToken, teacherToken, otherTeacherToken, managerToken;
    private String childInClass, childInAnotherGrade;

    @BeforeEach void seed() throws Exception {
        school(A, "Notice Academy", "ANSCHA");
        teacher(TEACHER_A, A, "a@an.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        teacher(TEACHER_A2, A, "a2@an.test", "Ms Dana", "[\"english\"]", "british", "[3]");
        user(MANAGER_A, A, "m@an.test", "MANAGERIAL");
        klass(CLASS_A1, A, "british", 1, "math", TEACHER_A);
        klass(CLASS_OTHER, A, "british", 3, "english", TEACHER_A2);

        adminToken = adminToken();
        enableTeacherFeatures(adminToken, A);
        teacherToken = token(TEACHER_A, "TEACHER", A);
        otherTeacherToken = token(TEACHER_A2, "TEACHER", A);
        managerToken = token(MANAGER_A, "MANAGERIAL", A);

        childInClass = child("Maya", "ANSCHA", "british", 1);
        childInAnotherGrade = child("Omar", "ANSCHA", "british", 3);
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void a_note_reaches_the_parents_of_that_class_and_nobody_else() throws Exception {
        var created = postNote(CLASS_A1, "Tomorrow we start subtraction", "غدا نبدأ الطرح", null);
        assertThat(created.get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(created.get("curriculum").asText()).isEqualTo("british");
        assertThat(created.get("grade").asInt()).isEqualTo(1);

        var mine = parentGet("/children/" + childInClass + "/announcements");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("bodyEn").asText()).isEqualTo("Tomorrow we start subtraction");
        assertThat(mine.get(0).get("bodyAr").asText()).isEqualTo("غدا نبدأ الطرح");
        assertThat(mine.get(0).get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(mine.get(0).get("teacherPhotoUrl").asText()).startsWith("https://");
        assertThat(mine.get(0).toString()).as("no school internals reach the app").doesNotContain("teacherId").doesNotContain("schoolId");

        assertThat(parentGet("/children/" + childInAnotherGrade + "/announcements")).isEmpty();
    }

    @Test void an_expired_note_stays_in_her_list_and_leaves_the_app() throws Exception {
        var soon = postNote(CLASS_A1, "Bring your reading book", null, Instant.now().plus(2, ChronoUnit.SECONDS).toEpochMilli());
        assertThat(parentGet("/children/" + childInClass + "/announcements")).hasSize(1);

        // rather than sleeping: move the expiry into the past through the repository, which is what time would do
        var row = announcementRows.findById(soon.get("id").asText()).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        announcementRows.save(row);

        assertThat(parentGet("/children/" + childInClass + "/announcements")).isEmpty();
        var hers = json(mvc.perform(as(get("/teacher/announcements"), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(hers.toString()).contains("Bring your reading book");
    }

    @Test void a_teacher_may_only_post_to_her_own_classes_and_remove_her_own_notes() throws Exception {
        var refused = json(mvc.perform(as(post("/teacher/announcements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + CLASS_OTHER + "\",\"bodyEn\":\"Not mine\"}"), teacherToken))
                .andExpect(status().isForbidden()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("forbidden");

        String id = postNote(CLASS_A1, "Mine", null, null).get("id").asText();
        mvc.perform(as(delete("/teacher/announcements/" + id), otherTeacherToken)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/teacher/announcements/" + id), teacherToken)).andExpect(status().isNoContent());
        assertThat(parentGet("/children/" + childInClass + "/announcements")).isEmpty();
    }

    @Test void a_managerial_user_reads_the_schools_notes_and_writes_none() throws Exception {
        String id = postNote(CLASS_A1, "Reading week", null, null).get("id").asText();

        var seen = json(mvc.perform(as(get("/teacher/announcements"), managerToken)).andExpect(status().isOk()).andReturn());
        assertThat(seen.toString()).contains("Reading week");

        mvc.perform(as(post("/teacher/announcements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + CLASS_A1 + "\",\"bodyEn\":\"Mine now\"}"), managerToken)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/teacher/announcements/" + id), managerToken)).andExpect(status().isForbidden());
    }

    @Test void a_note_is_validated_before_it_is_stored() throws Exception {
        mvc.perform(as(post("/teacher/announcements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + CLASS_A1 + "\",\"bodyEn\":\"  \"}"), teacherToken)).andExpect(status().isBadRequest());
        mvc.perform(as(post("/teacher/announcements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + CLASS_A1 + "\",\"bodyEn\":\"Past\",\"expiresAt\":1}"), teacherToken))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/teacher/announcements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"no-such-class\",\"bodyEn\":\"Nowhere\"}"), teacherToken)).andExpect(status().isNotFound());
    }

    private com.fasterxml.jackson.databind.JsonNode postNote(String classId, String en, String ar, Long expiresAt) throws Exception {
        String body = "{\"classId\":\"" + classId + "\",\"bodyEn\":\"" + en + "\""
                + (ar == null ? "" : ",\"bodyAr\":\"" + ar + "\"")
                + (expiresAt == null ? "" : ",\"expiresAt\":" + expiresAt) + "}";
        return json(mvc.perform(as(post("/teacher/announcements").contentType(MediaType.APPLICATION_JSON).content(body), teacherToken))
                .andExpect(status().isCreated()).andReturn());
    }
}
