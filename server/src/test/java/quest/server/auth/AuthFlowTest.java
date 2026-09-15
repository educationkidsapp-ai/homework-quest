package quest.server.auth;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import quest.server.ApiTestSupport;
import quest.server.mail.RecordingMailer;

/** P1.3 §5: sign-in, refresh rotation and reuse detection, sign-out, the password flows and the rate limit. */
@Import(RecordingMailer.Config.class)
class AuthFlowTest extends ApiTestSupport {
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RecordingMailer mailer;

    private static final String PASSWORD = "first-password-1";

    @Test void sign_in_gives_an_access_token_a_refresh_token_and_who_i_am() throws Exception {
        var user = user("signin", "MANAGERIAL", "default");
        var session = signIn(user.getEmail(), PASSWORD);

        assertThat(session.get("token").asText()).startsWith("admin.");
        assertThat(session.get("refreshToken").asText()).isNotBlank();
        assertThat(session.get("role").asText()).isEqualTo("MANAGERIAL");
        assertThat(session.get("schoolId").asText()).isEqualTo("default");
        assertThat(session.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(session.get("expiresAt").asLong())                                   // the access token is short (15 min)
                .isBetween(Instant.now().toEpochMilli(), Instant.now().plusSeconds(20 * 60).toEpochMilli());

        var me = json(mvc.perform(admin(get("/me"), session.get("token").asText())).andExpect(status().isOk()).andReturn());
        assertThat(me.get("email").asText()).isEqualTo(user.getEmail());
        assertThat(me.get("impersonatedBy").isNull()).isTrue();
        assertThat(me.get("language").asText()).isEqualTo("en");

        var permissions = json(mvc.perform(admin(get("/me/permissions"), session.get("token").asText())).andExpect(status().isOk()).andReturn());
        assertThat(permissions.get("role").asText()).isEqualTo("MANAGERIAL");
        assertThat(permissions.get("readOnly").asBoolean()).isFalse();
        assertThat(permissions.get("permissions").toString()).contains("user.invite").doesNotContain("school.write");
    }

    @Test void a_disabled_or_invited_account_cannot_sign_in() throws Exception {
        var disabled = user("disabled", "TEACHER", "default");
        disabled.setStatus("disabled"); users.save(disabled);
        mvc.perform(signInRequest(disabled.getEmail(), PASSWORD)).andExpect(status().isUnauthorized());

        var invited = user("invited", "TEACHER", "default");
        invited.setStatus("invited"); users.save(invited);
        mvc.perform(signInRequest(invited.getEmail(), PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test void refresh_rotates_and_reusing_an_old_token_kills_the_family() throws Exception {
        var user = user("rotate", "TEACHER", "default");
        String first = signIn(user.getEmail(), PASSWORD).get("refreshToken").asText();

        var rotated = json(mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken", first)))
                .andExpect(status().isOk()).andReturn());
        String second = rotated.get("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(rotated.get("token").asText()).startsWith("admin.");

        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken", first)))
                .andExpect(status().isUnauthorized());                                   // the rotated-away token is theft
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken", second)))
                .andExpect(status().isUnauthorized());                                   // …and it took the family with it
    }

    @Test void sign_out_revokes_the_refresh_token() throws Exception {
        var user = user("signout", "TEACHER", "default");
        String refresh = signIn(user.getEmail(), PASSWORD).get("refreshToken").asText();
        mvc.perform(post("/auth/sign-out").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken", refresh))).andExpect(status().isNoContent());
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken", refresh))).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/sign-out").contentType(MediaType.APPLICATION_JSON).content(body("refreshToken", refresh))).andExpect(status().isNoContent());
    }

    @Test void forgot_password_mails_a_one_time_link_that_sets_a_new_password() throws Exception {
        var user = user("forgot", "TEACHER", "default");
        mailer.clear();
        mvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body("email", user.getEmail())))
                .andExpect(status().isNoContent());

        var mail = mailer.last();
        assertThat(mail.to()).isEqualTo(user.getEmail());
        assertThat(mail.subject()).contains("reset your password");
        String token = mail.token();

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("token", token).put("newPassword", "a-brand-new-one").toString()))
                .andExpect(status().isNoContent());

        assertThat(signIn(user.getEmail(), "a-brand-new-one").get("token").asText()).startsWith("admin.");
        mvc.perform(signInRequest(user.getEmail(), PASSWORD)).andExpect(status().isUnauthorized());

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)       // the link is one-time
                .content(mapper.createObjectNode().put("token", token).put("newPassword", "yet-another-one").toString()))
                .andExpect(status().isUnauthorized());
    }

    /** The page must not answer differently — in words or in milliseconds — for an address that has an account. */
    @Test void an_unknown_and_a_known_address_both_get_a_204_and_the_mail_leaves_after_the_commit() throws Exception {
        var user = user("timing", "TEACHER", "default");
        mailer.clear();

        mvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body("email", "nobody@nowhere.test")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body("email", user.getEmail())))
                .andExpect(status().isNoContent());

        var mail = mailer.last();
        assertThat(mail.to()).isEqualTo(user.getEmail());
        assertThat(mail.inTransaction()).as("the mail leaves after the transaction commits, not inside it").isFalse();
        assertThat(mail.thread()).as("…and off the thread that answered the request").isNotEqualTo(Thread.currentThread().getName());
        assertThat(mailer.settled()).as("the unknown address is never mailed").hasSize(1);
    }

    /** A link sets a password; it never changes what the account is. */
    @Test void a_reset_link_cannot_revive_a_disabled_account_or_finish_an_invite() throws Exception {
        for (String status : new String[] {"disabled", "invited"}) {
            var user = user("reset-" + status, "TEACHER", "default");
            mailer.clear();
            mvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body("email", user.getEmail())))
                    .andExpect(status().isNoContent());
            String token = mailer.last().token();

            user.setStatus(status); users.save(user);

            mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)       // refused in the words of a stale link
                    .content(mapper.createObjectNode().put("token", token).put("newPassword", "sneaking-back-in").toString()))
                    .andExpect(status().isUnauthorized());

            var after = users.findById(user.getId()).orElseThrow();
            assertThat(after.getStatus()).as("the reset did not touch the account's status").isEqualTo(status);
            assertThat(encoder.matches("sneaking-back-in", after.getPasswordHash())).as("nor its password").isFalse();
            mvc.perform(signInRequest(user.getEmail(), "sneaking-back-in")).andExpect(status().isUnauthorized());
        }
    }

    /** A forgot-password link for an address that never had one must not become an account either. */
    @Test void an_inactive_account_is_not_mailed_a_reset_link_at_all() throws Exception {
        var user = user("nolink", "TEACHER", "default");
        user.setStatus("disabled"); users.save(user);
        mailer.clear();
        mvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body("email", user.getEmail())))
                .andExpect(status().isNoContent());
        assertThat(mailer.settled()).isEmpty();
    }

    @Test void the_first_login_is_gated_until_the_password_is_changed() throws Exception {
        var user = user("mustchange", "TEACHER", "default");
        user.setMustChangePassword(true); users.save(user);

        var session = signIn(user.getEmail(), PASSWORD);
        assertThat(session.get("mustChangePassword").asBoolean()).isTrue();
        String token = session.get("token").asText();

        mvc.perform(admin(post("/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("currentPassword", "not-my-password").put("newPassword", "the-second-one").toString()), token))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post("/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("currentPassword", PASSWORD).put("newPassword", "short").toString()), token))
                .andExpect(status().isBadRequest());                                     // 10 characters minimum
        mvc.perform(admin(post("/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("currentPassword", PASSWORD).put("newPassword", "the-second-one").toString()), token))
                .andExpect(status().isNoContent());

        assertThat(json(mvc.perform(admin(get("/me"), token)).andExpect(status().isOk()).andReturn()).get("mustChangePassword").asBoolean()).isFalse();
        assertThat(signIn(user.getEmail(), "the-second-one").get("mustChangePassword").asBoolean()).isFalse();
    }

    @Test void ten_wrong_passwords_per_email_and_ip_earn_a_429() throws Exception {
        var user = user("ratelimit", "TEACHER", "default");
        for (int attempt = 0; attempt < 10; attempt++)
            mvc.perform(signInRequest(user.getEmail(), "wrong-password-" + attempt)).andExpect(status().isUnauthorized());
        mvc.perform(signInRequest(user.getEmail(), "wrong-password-11")).andExpect(status().isTooManyRequests());
        mvc.perform(signInRequest(user.getEmail(), PASSWORD)).andExpect(status().isTooManyRequests());   // even the right one waits
    }

    /**
     * The bypass the review found: `X-Forwarded-For` is appended to by each hop, so its first entry is whatever the
     * caller typed. The limit keys on the peer address instead, and a fresh header per attempt buys nothing.
     */
    @Test void a_rotating_x_forwarded_for_does_not_buy_more_attempts() throws Exception {
        var user = user("spoofer", "TEACHER", "default");
        for (int attempt = 0; attempt < 10; attempt++)
            mvc.perform(signInRequest(user.getEmail(), "wrong-password-" + attempt)
                    .header("X-Forwarded-For", "10.0.0." + attempt + ", 35.191.0.1")).andExpect(status().isUnauthorized());

        mvc.perform(signInRequest(user.getEmail(), "wrong-password-11").header("X-Forwarded-For", "10.0.0.240, 35.191.0.1"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(signInRequest(user.getEmail().toUpperCase(java.util.Locale.ROOT), PASSWORD)     // …and the bucket is the normalised email
                .header("X-Forwarded-For", "203.0.113.7")).andExpect(status().isTooManyRequests());
    }

    // ---- helpers

    private Entities.UserEntity user(String label, String role, String schoolId) {
        var u = new Entities.UserEntity();
        u.setId(UUID.randomUUID().toString());
        u.setEmail(label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@school.test");
        u.setPasswordHash(encoder.encode(PASSWORD)); u.setRole(role); u.setSchoolId(schoolId); u.setStatus("active");
        u.setDisplayName(label); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        return users.save(u);
    }

    private JsonNode signIn(String email, String password) throws Exception {
        return json(mvc.perform(signInRequest(email, password)).andExpect(status().isOk()).andReturn());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signInRequest(String email, String password) {
        return post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("email", email).put("password", password).toString());
    }

    private String body(String field, String value) { return mapper.createObjectNode().put(field, value).toString(); }
}
