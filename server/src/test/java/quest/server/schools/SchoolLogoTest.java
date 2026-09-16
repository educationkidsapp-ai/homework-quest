package quest.server.schools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.UserRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;

/**
 * §6 screen 1: "School logo appears after the email is typed (looked up by domain) with a fade-in."
 *
 * <p>The interesting half is what it refuses. The route is public, so it must not become a way to find out which
 * addresses or domains have accounts: it answers only when the domain belongs to <em>exactly one</em> school, and
 * every other case — no match, several schools on one domain, a malformed address, a suspended school — is the same
 * 204 with no body.
 */
class SchoolLogoTest extends ApiTestSupport {
    private static final String LOGO = "https://cdn.example.test/al-noor.png";

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;

    @BeforeEach void seed() throws Exception {
        var noor = school("logo-noor", "Al Noor", "LOGO01");
        school("logo-valley", "Green Valley", "LOGO02");
        school("logo-shared-a", "Shared One", "LOGO03");
        school("logo-shared-b", "Shared Two", "LOGO04");
        var quiet = school("logo-quiet", "Quiet School", "LOGO05");
        quiet.setStatus("suspended");
        schools.save(quiet);

        user("logo-user-noor", "logo-noor", "sara@logo-noor.test");
        user("logo-user-valley", "logo-valley", "omar@logo-valley.test");
        user("logo-user-shared-a", "logo-shared-a", "one@logo-shared.test");
        user("logo-user-shared-b", "logo-shared-b", "two@logo-shared.test");
        user("logo-user-quiet", "logo-quiet", "head@logo-quiet.test");
        // A platform ADMIN has no school, so her domain must not resolve to one either.
        user("logo-user-admin", null, "boss@logo-platform.test");

        if (noor.getThemeJson() == null)
            mvc.perform(admin(put("/admin/schools/logo-noor/theme").contentType(MediaType.APPLICATION_JSON).content("""
                    {"logoUrl":"%s","appName":"Al Noor","primary":"#FFFFFF","primaryInk":"#201E1D","accent":"#CC2A0F",
                     "ground":"#F3F2F2","softBorder":"#D9D6D2","mascotColor":"#41708F","fontChoice":"nunito"}
                    """.formatted(LOGO)), adminToken())).andExpect(status().isOk());
    }

    @Test void an_address_of_a_school_answers_its_name_and_logo() throws Exception {
        var body = json(mvc.perform(get("/schools/logo").param("email", "anyone@logo-noor.test"))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("name").asText()).isEqualTo("Al Noor");
        assertThat(body.get("logoUrl").asText()).isEqualTo(LOGO);
    }

    @Test void the_domain_is_matched_whole_and_case_insensitively() throws Exception {
        assertThat(name("SARA@LOGO-NOOR.TEST")).isEqualTo("Al Noor");
        assertThat(name(" sara@logo-noor.test ")).isEqualTo("Al Noor");
        // A school with no theme of its own still has a name; the logo is simply absent.
        var valley = json(mvc.perform(get("/schools/logo").param("email", "x@logo-valley.test")).andExpect(status().isOk()).andReturn());
        assertThat(valley.get("name").asText()).isEqualTo("Green Valley");
    }

    @Test void a_domain_that_belongs_to_nobody_or_to_several_schools_says_nothing() throws Exception {
        noContent("stranger@logo-nobody.test");
        noContent("someone@logo-shared.test");                                       // two schools share it: no answer at all
        noContent("head@logo-quiet.test");                                           // suspended school
        noContent("boss@logo-platform.test");                                        // the platform admin has no school
    }

    @Test void a_domain_is_never_a_wildcard_and_a_malformed_address_is_simply_nothing() throws Exception {
        noContent("sara@%.test");                                               // a LIKE wildcard must not reach the query
        noContent("sara@_ogo-noor.test");
        noContent("sara@logo-noor.test.evil");                                     // a suffix, not the domain
        noContent("noor.test");                                                 // no @ at all
        noContent("a@b@logo-noor.test");
        noContent("sara@");
        noContent("@logo-noor.test");
        noContent("sara@localhost");                                            // no dot: not a domain
        mvc.perform(get("/schools/logo")).andExpect(status().isNoContent());    // no parameter at all
    }

    @Test void the_route_is_public_and_carries_nothing_else_about_the_school() throws Exception {
        var body = mvc.perform(get("/schools/logo").param("email", "sara@logo-noor.test"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).as("no join code, no id, no user list").doesNotContain("LOGO01").doesNotContain("logo-noor")
                .doesNotContain("sara@");
        assertThat(json(mvc.perform(get("/schools/logo").param("email", "sara@logo-noor.test")).andReturn()).size())
                .as("name and logoUrl, and nothing more").isEqualTo(2);
    }

    /**
     * The route is throttled like sign-in, in a bucket of its own. One address is flooded here, which fills only that
     * address's narrow bucket — a 429 for `flood@` must not cost anybody else their lookups, nor a real sign-in its
     * ten attempts.
     */
    @Test void a_flood_of_lookups_for_one_address_is_throttled() throws Exception {
        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            int status = mvc.perform(get("/schools/logo").param("email", "flood@logo-noor.test")).andReturn().getResponse().getStatus();
            if (status == 429) break;
            assertThat(status).as("attempt %d", i).isEqualTo(200);
            allowed++;
        }
        assertThat(allowed).as("a handful, then throttled").isBetween(1, 12);
        mvc.perform(get("/schools/logo").param("email", "flood@logo-noor.test")).andExpect(status().isTooManyRequests());
        // Another address is untouched: the bucket is per address, and it is not sign-in's.
        mvc.perform(get("/schools/logo").param("email", "sara@logo-noor.test")).andExpect(status().isOk());
        mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"flood@logo-noor.test\",\"password\":\"whatever-it-is\"}")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private String name(String email) throws Exception {
        return json(mvc.perform(get("/schools/logo").param("email", email)).andExpect(status().isOk()).andReturn())
                .get("name").asText();
    }

    private void noContent(String email) throws Exception {
        var result = mvc.perform(get("/schools/logo").param("email", email)).andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    private SchoolEntity school(String id, String name, String code) {
        return schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    private void user(String id, String schoolId, String email) {
        if (users.existsById(id)) return;
        var u = new UserEntity();
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(email); u.setPasswordHash("x");
        u.setRole(schoolId == null ? "ADMIN" : "MANAGERIAL"); u.setStatus("active");
        u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        users.save(u);
    }
}
