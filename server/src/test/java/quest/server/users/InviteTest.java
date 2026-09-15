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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;
import quest.server.auth.InviteRepository;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.mail.RecordingMailer;

/** P1.3 §5: an Admin invites a teacher, the link creates the account, and a used or expired link is gone. */
@Import(RecordingMailer.Config.class)
class InviteTest extends ApiTestSupport {
    @Autowired RecordingMailer mailer;
    @Autowired InviteRepository invites;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;

    @Test void invite_accept_and_sign_in_as_a_teacher_with_a_profile() throws Exception {
        String token = adminToken();
        String schoolId = newSchool(token, "Al Noor Primary").get("id").asText();
        String email = "sara-" + UUID.randomUUID().toString().substring(0, 8) + "@alnoor.test";

        mailer.clear();
        var invite = json(mvc.perform(admin(post("/admin/schools/" + schoolId + "/invites").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","role":"TEACHER","teacherProfile":{"displayName":"Ms Sara","subjects":["math"],"curriculum":"british","grades":[1,2],"bioEn":"Maths"}}
                        """.formatted(email)), token)).andExpect(status().isOk()).andReturn());
        assertThat(invite.get("role").asText()).isEqualTo("TEACHER");
        assertThat(invite.get("schoolId").asText()).isEqualTo(schoolId);
        assertThat(invite.has("token")).as("the one-time token never leaves through the API").isFalse();

        var mail = mailer.last();
        assertThat(mail.to()).isEqualTo(email);
        assertThat(mail.subject()).contains("Al Noor Primary");
        assertThat(mail.text()).contains("/panel/accept-invite?token=");
        String link = mail.token();

        // the account exists but cannot be used until the link is accepted
        assertThat(users.findByEmailIgnoreCase(email).orElseThrow().getStatus()).isEqualTo("invited");
        mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content(signIn(email, "whatever-1234"))).andExpect(status().isUnauthorized());

        var info = json(mvc.perform(get("/invites/" + link)).andExpect(status().isOk()).andReturn());
        assertThat(info.get("email").asText()).isEqualTo(email);
        assertThat(info.get("role").asText()).isEqualTo("TEACHER");
        assertThat(info.get("schoolName").asText()).isEqualTo("Al Noor Primary");

        var accepted = json(mvc.perform(post("/invites/" + link + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"sara-password-1\",\"displayName\":\"Ms Sara\"}")).andExpect(status().isOk()).andReturn());
        assertThat(accepted.get("role").asText()).isEqualTo("TEACHER");
        assertThat(accepted.get("schoolId").asText()).isEqualTo(schoolId);
        assertThat(accepted.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(accepted.get("refreshToken").asText()).isNotBlank();

        var signedIn = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content(signIn(email, "sara-password-1"))).andExpect(status().isOk()).andReturn());
        assertThat(signedIn.get("role").asText()).isEqualTo("TEACHER");
        assertThat(signedIn.get("displayName").asText()).isEqualTo("Ms Sara");

        var user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getStatus()).isEqualTo("active");
        var profile = teachers.findById(user.getId()).orElseThrow();
        assertThat(profile.getSubjectsJson()).contains("math");
        assertThat(profile.getCurriculum()).isEqualTo("british");
        assertThat(profile.getGradesJson()).isEqualTo("[1,2]");

        mvc.perform(get("/invites/" + link)).andExpect(status().isGone());                       // used
        mvc.perform(post("/invites/" + link + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"another-password\"}")).andExpect(status().isGone());
    }

    @Test void an_expired_invitation_is_gone() throws Exception {
        String token = adminToken();
        String schoolId = newSchool(token, "Sunrise School").get("id").asText();
        String email = "late-" + UUID.randomUUID().toString().substring(0, 8) + "@sunrise.test";

        mailer.clear();
        mvc.perform(admin(post("/admin/schools/" + schoolId + "/invites").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"role\":\"MANAGERIAL\"}".formatted(email)), token)).andExpect(status().isOk());
        String link = mailer.last().token();

        var row = invites.findAll().stream().filter(i -> i.getEmail().equals(email)).findFirst().orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(60));
        invites.save(row);

        mvc.perform(get("/invites/" + link)).andExpect(status().isGone());
        mvc.perform(post("/invites/" + link + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"too-late-now-1\"}")).andExpect(status().isGone());
    }

    @Test void a_school_code_is_generated_and_a_parent_can_look_the_school_up_by_it() throws Exception {
        var school = newSchool(adminToken(), "Code School");
        assertThat(school.get("code").asText()).matches("^[A-Z0-9]{6}$");

        var joined = json(mvc.perform(get("/schools/by-code/" + school.get("code").asText().toLowerCase())).andExpect(status().isOk()).andReturn());
        assertThat(joined.get("name").asText()).isEqualTo("Code School");
        assertThat(joined.get("curriculumOptions").toString()).contains("british");
        mvc.perform(get("/schools/by-code/ZZZZZZ")).andExpect(status().isNotFound());
    }

    private JsonNode newSchool(String token, String name) throws Exception {
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"curriculumOptions\":[\"british\"],\"gradeOptions\":[1,2,3]}".formatted(name)), token))
                .andExpect(status().isOk()).andReturn());
    }

    private String signIn(String email, String password) { return mapper.createObjectNode().put("email", email).put("password", password).toString(); }
}
