package quest.server.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import quest.server.ApiTestSupport;
import quest.server.auth.AuditLogRepository;
import quest.server.auth.Entities;
import quest.server.auth.UserRepository;

/** P1.3 §5 "View as…": an Admin may look through a teacher's eyes, may not touch, and leaves a trail. */
class ImpersonationTest extends ApiTestSupport {
    @Autowired UserRepository users;
    @Autowired AuditLogRepository auditLog;
    @Autowired PasswordEncoder encoder;

    @Test void an_admin_sees_what_a_teacher_sees_but_cannot_write() throws Exception {
        String adminToken = adminToken();
        var teacher = teacher();

        var view = json(mvc.perform(admin(post("/admin/users/" + teacher.getId() + "/impersonate"), adminToken)).andExpect(status().isOk()).andReturn());
        String readOnlyToken = view.get("token").asText();
        assertThat(view.get("role").asText()).isEqualTo("TEACHER");
        assertThat(view.get("schoolId").asText()).isEqualTo("default");
        assertThat(view.get("refreshToken").isNull()).as("a read-only view is not a session you can extend").isTrue();

        var me = json(mvc.perform(admin(get("/me"), readOnlyToken)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("email").asText()).isEqualTo(teacher.getEmail());
        assertThat(me.get("impersonatedBy").asText()).isEqualTo(adminUserId());

        assertThat(json(mvc.perform(admin(get("/me/permissions"), readOnlyToken)).andExpect(status().isOk()).andReturn())
                .get("readOnly").asBoolean()).isTrue();

        mvc.perform(admin(post("/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"teacher-password\",\"newPassword\":\"not-on-my-watch\"}"), readOnlyToken))
                .andExpect(status().isForbidden());
        mvc.perform(admin(patch("/admin/users/" + teacher.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Renamed\"}"), readOnlyToken))
                .andExpect(status().isForbidden());

        assertThat(auditLog.findAll()).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo("user.impersonate");
            assertThat(row.getTargetId()).isEqualTo(teacher.getId());
            assertThat(row.getActorUserId()).isEqualTo(adminUserId());
        });
        var requests = auditLog.findAll().stream()
                .filter(r -> "user.impersonate.request".equals(r.getAction()) && teacher.getId().equals(r.getTargetId())).toList();
        assertThat(requests).as("every request under the view is logged, refused ones included").hasSize(4);
        assertThat(requests).allSatisfy(r -> assertThat(r.getActorUserId()).isEqualTo(adminUserId()));
        assertThat(requests.stream().map(r -> r.getDetailsJson()).toList())
                .anySatisfy(details -> assertThat(details).contains("\"path\":\"/me\""))
                .anySatisfy(details -> assertThat(details).contains("/admin/users/"));

        mvc.perform(admin(get("/me"), readOnlyToken)).andExpect(status().isOk());      // …at most one row a minute per path
        assertThat(auditLog.findAll().stream().filter(r -> "user.impersonate.request".equals(r.getAction())
                && teacher.getId().equals(r.getTargetId())).toList()).hasSize(4);
    }

    @Test void only_an_active_teacher_or_managerial_user_can_be_viewed_as() throws Exception {
        String adminToken = adminToken();
        mvc.perform(admin(post("/admin/users/" + adminUserId() + "/impersonate"), adminToken)).andExpect(status().isBadRequest());

        var disabled = teacher();
        disabled.setStatus("disabled"); users.save(disabled);
        mvc.perform(admin(post("/admin/users/" + disabled.getId() + "/impersonate"), adminToken)).andExpect(status().isBadRequest());

        mvc.perform(admin(post("/admin/users/no-such-user/impersonate"), adminToken)).andExpect(status().isNotFound());
    }

    private Entities.UserEntity teacher() {
        var u = new Entities.UserEntity();
        u.setId(UUID.randomUUID().toString());
        u.setEmail("view-as-" + UUID.randomUUID().toString().substring(0, 8) + "@school.test");
        u.setPasswordHash(encoder.encode("teacher-password")); u.setRole("TEACHER"); u.setSchoolId("default");
        u.setStatus("active"); u.setDisplayName("Ms Sara"); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        return users.save(u);
    }

    private String adminUserId() { return users.findByEmailIgnoreCase("admin@test.local").orElseThrow().getId(); }
}
