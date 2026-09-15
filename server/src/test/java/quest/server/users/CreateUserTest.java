package quest.server.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;

/**
 * P1.9 `POST /admin/schools/{id}/users`: the Admin makes an account outright instead of emailing an invitation. It is
 * active at once, signs in with the password she hands over, and must replace it there and then.
 */
class CreateUserTest extends ApiTestSupport {
    private static final String PASSWORD = "handed-over-1234";

    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired AdminJwtService jwt;
    @Autowired PasswordEncoder encoder;

    @Test void an_admin_creates_an_active_teacher_who_must_change_her_password() throws Exception {
        String token = adminToken();
        String school = newSchool(token).get("id").asText();
        String email = "created-" + suffix() + "@school.test";

        var created = json(mvc.perform(admin(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content(body(email, "TEACHER", PASSWORD, "\"displayName\":\"Ms Hana\",\"teacherProfile\":{\"subjects\":[\"math\"],\"curriculum\":\"british\",\"grades\":[1,2]}")), token))
                .andExpect(status().isCreated()).andReturn());

        assertThat(created.get("email").asText()).isEqualTo(email);
        assertThat(created.get("role").asText()).isEqualTo("TEACHER");
        assertThat(created.get("schoolId").asText()).isEqualTo(school);
        assertThat(created.get("status").asText()).isEqualTo("active");
        assertThat(created.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(created.toString()).as("no password material ever leaves the server").doesNotContain(PASSWORD).doesNotContain("$2");

        var profile = teachers.findById(created.get("id").asText());
        assertThat(profile).as("a TEACHER gets her profile with the account").isPresent();
        assertThat(profile.orElseThrow().getCurriculum()).isEqualTo("british");

        var signedIn = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("email", email).put("password", PASSWORD).toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(signedIn.get("mustChangePassword").asBoolean()).isTrue();

        var audit = json(mvc.perform(admin(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content(body(email, "TEACHER", PASSWORD, null)), token)).andExpect(status().isConflict()).andReturn());
        assertThat(audit.get("code").asText()).isEqualTo("conflict");
    }

    @Test void a_managerial_user_is_created_without_a_teacher_profile() throws Exception {
        String token = adminToken();
        String school = newSchool(token).get("id").asText();
        var created = json(mvc.perform(admin(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content(body("created-" + suffix() + "@school.test", "MANAGERIAL", PASSWORD, null)), token)).andExpect(status().isCreated()).andReturn());
        assertThat(teachers.findById(created.get("id").asText())).isEmpty();
    }

    @Test void the_role_the_password_and_the_school_are_all_checked() throws Exception {
        String token = adminToken();
        String school = newSchool(token).get("id").asText();
        mvc.perform(admin(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content(body("created-" + suffix() + "@school.test", "ADMIN", PASSWORD, null)), token)).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content(body("created-" + suffix() + "@school.test", "TEACHER", "short", null)), token)).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/schools/no-such-school/users").contentType(MediaType.APPLICATION_JSON)
                .content(body("created-" + suffix() + "@school.test", "TEACHER", PASSWORD, null)), token)).andExpect(status().isNotFound());
    }

    @Test void only_the_admin_may_create_an_account() throws Exception {
        String token = adminToken();
        String school = newSchool(token).get("id").asText();
        var manager = user("MANAGERIAL", school);
        String managerToken = jwt.issue(manager.getId(), manager.getEmail(), "MANAGERIAL", school).token();

        mvc.perform(admin(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content(body("created-" + suffix() + "@school.test", "TEACHER", PASSWORD, null)), managerToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content(body("created-" + suffix() + "@school.test", "TEACHER", PASSWORD, null)))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers

    private String body(String email, String role, String password, String extra) {
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\",\"password\":\"" + password + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private JsonNode newSchool(String token) throws Exception {
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Create User School %s\"}".formatted(suffix())), token)).andExpect(status().isOk()).andReturn());
    }

    private Entities.UserEntity user(String role, String schoolId) {
        var u = new Entities.UserEntity();
        u.setId(UUID.randomUUID().toString());
        u.setEmail("created-manager-" + suffix() + "@school.test");
        u.setPasswordHash(encoder.encode(PASSWORD)); u.setRole(role); u.setSchoolId(schoolId); u.setStatus("active");
        u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        return users.save(u);
    }

    private static String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
}
