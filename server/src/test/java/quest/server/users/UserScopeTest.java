package quest.server.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import quest.server.auth.Entities;
import quest.server.auth.UserRepository;

/**
 * P1.2 + P1.3: `users` is a tenant table now, so the dashboard users list is scoped twice — by `UserService`'s own
 * `schoolId` check and by the `school` Hibernate filter under it. The platform ADMIN (whose row has no school) still
 * reads across schools.
 */
class UserScopeTest extends ApiTestSupport {
    private static final String PASSWORD = "user-scope-pass-1";

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @Test void a_managerial_user_lists_their_own_school_whatever_they_ask_for() throws Exception {
        String adminToken = adminToken();
        String otherSchool = newSchool(adminToken).get("id").asText();

        var manager = user("MANAGERIAL", "default");
        var colleague = user("TEACHER", "default");
        var stranger = user("TEACHER", otherSchool);

        String token = signIn(manager);
        var mine = json(mvc.perform(admin(get("/admin/users"), token)).andExpect(status().isOk()).andReturn());
        assertThat(mine.toString()).contains(manager.getEmail()).contains(colleague.getEmail());
        assertThat(mine.toString()).as("another school's people are not in the list").doesNotContain(stranger.getEmail());
        assertThat(mine.toString()).as("nor is the platform admin, whose row has no school").doesNotContain("admin@test.local");

        var asked = json(mvc.perform(admin(get("/admin/users?schoolId=" + otherSchool), token)).andExpect(status().isOk()).andReturn());
        assertThat(asked.toString()).as("asking for another school changes nothing").doesNotContain(stranger.getEmail());

        var everyone = json(mvc.perform(admin(get("/admin/users"), adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(everyone.toString()).as("the Admin still reads across schools").contains(stranger.getEmail()).contains(manager.getEmail());
    }

    // ---- helpers

    private JsonNode newSchool(String token) throws Exception {
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Scope School %s\"}".formatted(suffix())), token)).andExpect(status().isOk()).andReturn());
    }

    private Entities.UserEntity user(String role, String schoolId) {
        var u = new Entities.UserEntity();
        u.setId(UUID.randomUUID().toString());
        u.setEmail("users-" + suffix() + "@school.test");
        u.setPasswordHash(encoder.encode(PASSWORD)); u.setRole(role); u.setSchoolId(schoolId); u.setStatus("active");
        u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        return users.save(u);
    }

    private String signIn(Entities.UserEntity user) throws Exception {
        return json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("email", user.getEmail()).put("password", PASSWORD).toString()))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private static String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
}
