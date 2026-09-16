package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * §5's teacher profile and §6 screen 13's restricted chooser, plus `PATCH /me` — the three things any dashboard
 * user may change about herself.
 */
class TeacherProfileTest extends TeacherTestSupport {
    private static final String A = "tp-school-a";
    private static final String TEACHER_A = "tp-teacher-a", MANAGER_A = "tp-manager-a";
    private static final String CLASS_A1 = "tp-school-a:british:1:math", CLASS_A2 = "tp-school-a:british:2:math";

    @Override String prefix() { return "tp-"; }

    private String adminToken, teacherToken, managerToken;

    @BeforeEach void seed() throws Exception {
        school(A, "Profile Academy", "TPSCHA");
        teacher(TEACHER_A, A, "a@tp.test", "Ms Sara", "[\"math\"]", "british", "[1,2]");
        user(MANAGER_A, A, "m@tp.test", "MANAGERIAL");
        klass(CLASS_A1, A, "british", 1, "math", TEACHER_A);
        klass(CLASS_A2, A, "british", 2, "math", TEACHER_A);
        klass("tp-school-a:british:3:english", A, "british", 3, "english", null);
        adminToken = adminToken();
        teacherToken = token(TEACHER_A, "TEACHER", A);
        managerToken = token(MANAGER_A, "MANAGERIAL", A);
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void a_teacher_reads_and_writes_her_own_profile() throws Exception {
        var mine = json(mvc.perform(as(get("/teacher/profile"), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(mine.get("userId").asText()).isEqualTo(TEACHER_A);
        assertThat(mine.get("displayName").asText()).isEqualTo("Ms Sara");
        assertThat(mine.get("curriculum").asText()).isEqualTo("british");
        assertThat(mine.get("subjects").get(0).asText()).isEqualTo("math");

        var saved = json(mvc.perform(as(put("/teacher/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Ms Sara Ahmed\",\"bioEn\":\"Ten years of Year 1 maths.\",\"grades\":[1,2,3]}"),
                teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(saved.get("displayName").asText()).isEqualTo("Ms Sara Ahmed");
        assertThat(saved.get("bioEn").asText()).isEqualTo("Ten years of Year 1 maths.");
        assertThat(saved.get("grades")).hasSize(3);
        // an absent field is left alone rather than cleared
        assertThat(saved.get("curriculum").asText()).isEqualTo("british");
    }

    /** `photoUrl` reaches an `img src` in the dashboard and the app: the `SafeText` rule, not a second one. */
    @Test void a_photo_url_must_be_https_and_free_of_attribute_breakers() throws Exception {
        for (String bad : new String[] {"http://cdn.test/x.png", "javascript:alert(1)", "https://cdn.test/\"x.png", "https://cdn.test/a b.png"}) {
            var refused = json(mvc.perform(as(put("/teacher/profile").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"photoUrl\":\"" + bad.replace("\"", "\\\"") + "\"}"), teacherToken))
                    .andExpect(status().isBadRequest()).andReturn());
            assertThat(refused.get("code").asText()).as(bad).isEqualTo("bad_request");
        }
        mvc.perform(as(put("/teacher/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"photoUrl\":\"https://cdn.example.test/sara.png\"}"), teacherToken)).andExpect(status().isOk());
    }

    @Test void the_chooser_offers_only_what_she_may_publish() throws Exception {
        var options = json(mvc.perform(as(get("/teacher/options"), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(options.get("complete").asBoolean()).isTrue();
        assertThat(options.get("curriculum").asText()).isEqualTo("british");
        assertThat(options.get("subjects")).hasSize(1);
        assertThat(options.get("subjects").get(0).asText()).isEqualTo("math");
        assertThat(options.toString()).contains(CLASS_A1).contains(CLASS_A2)
                .as("a class she does not own is not hers to publish into").doesNotContain("british:3:english");

        // and the server refuses exactly what the chooser does not offer (§5, `TenantGuard.lessonCreator`)
        var refused = json(mvc.perform(as(post("/admin/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"english\",\"date\":\"2027-05-04\",\"source\":\"manual\"}"),
                teacherToken)).andExpect(status().isForbidden()).andReturn());
        assertThat(refused.get("message").asText()).contains("subject:");
    }

    @Test void a_teacher_with_an_empty_profile_is_told_it_is_incomplete() throws Exception {
        user("tp-teacher-blank", A, "blank@tp.test", "TEACHER");
        var options = json(mvc.perform(as(get("/teacher/options"), token("tp-teacher-blank", "TEACHER", A)))
                .andExpect(status().isOk()).andReturn());
        assertThat(options.get("complete").asBoolean()).isFalse();
        assertThat(options.get("subjects")).isEmpty();
    }

    @Test void only_an_admin_reaches_another_teachers_profile() throws Exception {
        mvc.perform(as(get("/admin/users/" + TEACHER_A + "/teacher-profile"), teacherToken)).andExpect(status().isForbidden());
        mvc.perform(as(get("/admin/users/" + TEACHER_A + "/teacher-profile"), managerToken)).andExpect(status().isForbidden());

        var read = json(mvc.perform(as(get("/admin/users/" + TEACHER_A + "/teacher-profile"), adminToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(read.get("displayName").asText()).isEqualTo("Ms Sara");

        var written = json(mvc.perform(as(put("/admin/users/" + TEACHER_A + "/teacher-profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjects\":[\"math\",\"english\"]}"), adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(written.get("subjects")).hasSize(2);

        // there is no teacher profile on a Managerial account
        mvc.perform(as(get("/admin/users/" + MANAGER_A + "/teacher-profile"), adminToken)).andExpect(status().isNotFound());
    }

    @Test void a_managerial_user_has_no_teacher_profile_of_her_own() throws Exception {
        mvc.perform(as(get("/teacher/profile"), managerToken)).andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/options"), managerToken)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- PATCH /me

    @Test void any_dashboard_user_changes_her_own_name_photo_and_language() throws Exception {
        var updated = json(mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Ms Sara A.\",\"photoUrl\":\"https://cdn.example.test/s.png\",\"language\":\"ar\"}"),
                teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(updated.get("displayName").asText()).isEqualTo("Ms Sara A.");
        assertThat(updated.get("photoUrl").asText()).isEqualTo("https://cdn.example.test/s.png");
        assertThat(updated.get("language").asText()).isEqualTo("ar");
        assertThat(json(mvc.perform(as(get("/me"), teacherToken)).andExpect(status().isOk()).andReturn())
                .get("language").asText()).isEqualTo("ar");

        // a Managerial user and the platform Admin may change their own too
        mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"en\"}"), managerToken))
                .andExpect(status().isOk());
        mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Owner\"}"), adminToken))
                .andExpect(status().isOk());
    }

    @Test void patch_me_refuses_a_bad_language_and_a_non_https_photo() throws Exception {
        mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"fr\"}"), teacherToken))
                .andExpect(status().isBadRequest());
        mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON).content("{\"photoUrl\":\"http://cdn.test/x.png\"}"), teacherToken))
                .andExpect(status().isBadRequest());
    }

    /** It changes only those three things: role, status, school and email are somebody else's to change. */
    @Test void patch_me_cannot_move_a_teacher_to_another_role_or_school() throws Exception {
        mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Ms Sara\",\"role\":\"ADMIN\",\"schoolId\":\"default\",\"status\":\"disabled\"}"),
                teacherToken)).andExpect(status().isOk());
        var me = json(mvc.perform(as(get("/me"), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("role").asText()).isEqualTo("TEACHER");
        assertThat(me.get("schoolId").asText()).isEqualTo(A);
        assertThat(me.get("status").asText()).isEqualTo("active");
    }

    /** §5 "View as…" is read-only, and `PATCH /me` is a write like any other. */
    @Test void an_impersonated_session_may_not_patch_me() throws Exception {
        String impersonated = json(mvc.perform(as(post("/admin/users/" + TEACHER_A + "/impersonate"), adminToken))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
        mvc.perform(as(get("/me"), impersonated)).andExpect(status().isOk());
        var refused = json(mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Not me\"}"), impersonated)).andExpect(status().isForbidden()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("forbidden");
    }
}
