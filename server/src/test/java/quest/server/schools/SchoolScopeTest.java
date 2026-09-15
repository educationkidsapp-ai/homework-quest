package quest.server.schools;

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
 * P1.3 review: `school.read` is granted to TEACHER and MANAGERIAL, so the endpoints have to scope themselves — the
 * Hibernate tenant filter does not reach `findById`. A school that is not theirs is a 404, and its join code (which is
 * all a parent needs to attach a child to it) never appears in a payload of theirs.
 */
class SchoolScopeTest extends ApiTestSupport {
    private static final String PASSWORD = "school-scope-1";

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @Test void a_teacher_sees_only_their_own_school() throws Exception { onlyTheirOwnSchool("TEACHER"); }

    @Test void a_managerial_user_sees_only_their_own_school() throws Exception { onlyTheirOwnSchool("MANAGERIAL"); }

    private void onlyTheirOwnSchool(String role) throws Exception {
        String adminToken = adminToken();
        var mine = newSchool(adminToken, "Mine " + suffix());
        var theirs = newSchool(adminToken, "Theirs " + suffix());
        String mineId = mine.get("id").asText(), theirsId = theirs.get("id").asText(), theirsCode = theirs.get("code").asText();

        String token = signIn(user(role, mineId));

        var list = json(mvc.perform(admin(get("/admin/schools"), token)).andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(mineId);
        assertThat(list.get(0).get("code").asText()).isEqualTo(mine.get("code").asText());
        assertThat(list.toString()).as("no other tenant's join code is in the list").doesNotContain(theirsCode);

        var own = json(mvc.perform(admin(get("/admin/schools/" + mineId), token)).andExpect(status().isOk()).andReturn());
        assertThat(own.get("id").asText()).isEqualTo(mineId);

        mvc.perform(admin(get("/admin/schools/" + theirsId), token)).andExpect(status().isNotFound());

        var asAdmin = json(mvc.perform(admin(get("/admin/schools"), adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(asAdmin.toString()).as("the Admin still sees every school").contains(mineId).contains(theirsId);
    }

    // ---- helpers

    private JsonNode newSchool(String token, String name) throws Exception {
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"curriculumOptions\":[\"british\"],\"gradeOptions\":[1,2,3]}".formatted(name)), token))
                .andExpect(status().isOk()).andReturn());
    }

    private Entities.UserEntity user(String role, String schoolId) {
        var u = new Entities.UserEntity();
        u.setId(UUID.randomUUID().toString());
        u.setEmail("scope-" + suffix() + "@school.test");
        u.setPasswordHash(encoder.encode(PASSWORD)); u.setRole(role); u.setSchoolId(schoolId); u.setStatus("active");
        u.setDisplayName("Scope " + role); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        return users.save(u);
    }

    private String signIn(Entities.UserEntity user) throws Exception {
        return json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("email", user.getEmail()).put("password", PASSWORD).toString()))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private static String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
}
