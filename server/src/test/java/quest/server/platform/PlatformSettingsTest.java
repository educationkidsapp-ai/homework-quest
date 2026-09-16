package quest.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;
import quest.server.mail.PlatformName;

/**
 * §A: the product's name is data. It is seeded, Admin can change it, email subjects follow it, and a school's
 * `theme.appName` wins inside that school — the resolution order `GET /me.platformName` answers with.
 */
class PlatformSettingsTest extends ApiTestSupport {
    private static final String SEEDED_NAME = "Schools Dashboard";
    private static final String SEEDED_SHORT_NAME = "Schools";
    private static final String PASSWORD = "handed-over-1234";

    @Autowired PlatformSettingsService settings;
    @Autowired PlatformName platformName;

    /** Every test here may rename the platform; put the seeded name back so the others start where they expect. */
    @AfterEach void restoreTheSeededName() throws Exception {
        mvc.perform(admin(put("/admin/platform-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + SEEDED_NAME + "\",\"shortName\":\"" + SEEDED_SHORT_NAME + "\",\"logoUrl\":\"\"}"), adminToken()))
                .andExpect(status().isOk());
    }

    @Test void the_public_route_serves_the_seeded_name_and_nothing_private() throws Exception {
        var body = json(mvc.perform(get("/platform-settings")).andExpect(status().isOk()).andReturn());
        assertThat(body.get("name").asText()).isEqualTo(SEEDED_NAME);
        assertThat(body.get("shortName").asText()).isEqualTo(SEEDED_SHORT_NAME);
        assertThat(body.get("supportEmail").isNull()).as("the public route is name, shortName and logo only").isTrue();
        assertThat(body.get("defaultTheme").isNull()).isTrue();
    }

    @Test void admin_renames_the_platform_and_the_mail_subjects_follow() throws Exception {
        var token = adminToken();
        assertThat(platformName.get()).isEqualTo(SEEDED_NAME);

        var saved = json(mvc.perform(admin(put("/admin/platform-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Learning\",\"shortName\":\"Acme\",\"logoUrl\":\"https://cdn.test/acme.png\",\"supportEmail\":\"help@acme.test\"}"), token))
                .andExpect(status().isOk()).andReturn());
        assertThat(saved.get("name").asText()).isEqualTo("Acme Learning");
        assertThat(saved.get("supportEmail").asText()).isEqualTo("help@acme.test");
        assertThat(saved.get("defaultTheme").get("accent").asText()).isEqualTo("#CC2A0F");

        assertThat(platformName.get()).as("the rename is visible at once on the instance that made it").isEqualTo("Acme Learning");
        assertThat(json(mvc.perform(get("/platform-settings")).andReturn()).get("name").asText()).isEqualTo("Acme Learning");
        assertThat(json(mvc.perform(admin(get("/admin/platform-settings"), token)).andExpect(status().isOk()).andReturn())
                .get("logoUrl").asText()).isEqualTo("https://cdn.test/acme.png");
    }

    @Test void a_platform_default_theme_goes_through_the_same_contrast_check() throws Exception {
        var token = adminToken();
        var refused = json(mvc.perform(admin(put("/admin/platform-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultTheme\":{\"primary\":\"#FFFFFF\",\"primaryInk\":\"#FFFFFF\",\"accent\":\"#0B5D2E\",\"ground\":\"#F4F4F2\","
                        + "\"softBorder\":\"#D9D6D2\",\"mascotColor\":\"#2F5D7C\"}}"), token))
                .andExpect(status().isBadRequest()).andReturn());
        assertThat(refused.get("message").asText()).isEqualTo("primaryInk on primary is 1.0:1, needs 4.5:1");
        assertThat(settings.defaultThemeJson()).as("a refused theme is not stored").isNull();
    }

    @Test void me_resolves_the_school_app_name_then_the_platform_name() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        String email = "named-" + UUID.randomUUID().toString().substring(0, 8) + "@school.test";
        mvc.perform(admin(post("/admin/schools/" + school + "/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"MANAGERIAL\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Ms Noor\"}"), token))
                .andExpect(status().isCreated());
        String userToken = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isOk()).andReturn()).get("token").asText();

        // 3. no school appName and the seeded platform name: the seeded default
        assertThat(json(mvc.perform(admin(get("/me"), userToken)).andExpect(status().isOk()).andReturn())
                .get("platformName").asText()).isEqualTo(SEEDED_NAME);

        // 2. the platform is renamed and a user with no school appName follows it
        mvc.perform(admin(put("/admin/platform-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Learning\"}"), token)).andExpect(status().isOk());
        assertThat(json(mvc.perform(admin(get("/me"), userToken)).andReturn()).get("platformName").asText()).isEqualTo("Acme Learning");
        assertThat(json(mvc.perform(admin(get("/me"), token)).andReturn()).get("platformName").asText())
                .as("the platform ADMIN has no school, so she sees the platform's name").isEqualTo("Acme Learning");

        // 1. the school's own appName wins inside that school
        mvc.perform(admin(put("/admin/schools/" + school + "/theme").contentType(MediaType.APPLICATION_JSON)
                .content("{\"appName\":\"Al Noor Learning\",\"primary\":\"#FFFFFF\",\"primaryInk\":\"#1A1A1A\",\"accent\":\"#0B5D2E\","
                        + "\"ground\":\"#F4F4F2\",\"softBorder\":\"#D9D6D2\",\"mascotColor\":\"#2F5D7C\"}"), token)).andExpect(status().isOk());
        assertThat(json(mvc.perform(admin(get("/me"), userToken)).andReturn()).get("platformName").asText()).isEqualTo("Al Noor Learning");
        assertThat(json(mvc.perform(admin(get("/me"), token)).andReturn()).get("platformName").asText())
                .as("…and only inside it").isEqualTo("Acme Learning");
    }

    private String createSchool(String token) throws Exception {
        var body = "{\"name\":\"Named School " + UUID.randomUUID().toString().substring(0, 6) + "\",\"curriculumOptions\":[\"british\"],\"gradeOptions\":[1,2,3]}";
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON).content(body), token))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
    }
}
