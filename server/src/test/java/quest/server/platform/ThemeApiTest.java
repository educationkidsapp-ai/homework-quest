package quest.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;

/** §3: the public theme route (cached, ETagged), and what Admin's save is and is not allowed to be. */
class ThemeApiTest extends ApiTestSupport {

    /** A theme every pair of which clears 4.5:1 — the shape the tests below start from. */
    private static String goodTheme(String appName) {
        return """
            {"logoUrl":"https://cdn.test/al-noor.png","appName":"%s","primary":"#FFFFFF","primaryInk":"#1A1A1A",
             "accent":"#0B5D2E","ground":"#F4F4F2","softBorder":"#D9D6D2","mascotColor":"#2F5D7C",
             "worldPalettes":{"math":{"primary":"#6FC3FF","deep":"#3F9BE0","soft":"#EAF4FF","ink":"#1A1A1A"},
                              "english":{"primary":"#B69CFF","deep":"#7E63D8","soft":"#F1ECFF","ink":"#1A1A1A"}},
             "fontChoice":"baloo"}
            """.formatted(appName);
    }

    @Test void a_school_without_a_theme_is_shown_the_default_one() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        var theme = json(mvc.perform(get("/schools/" + school + "/theme")).andExpect(status().isOk()).andReturn());
        assertThat(theme.get("primaryInk").asText()).isEqualTo("#201E1D");
        assertThat(theme.get("accent").asText()).isEqualTo("#CC2A0F");
        assertThat(theme.get("fontChoice").asText()).isEqualTo("nunito");
        assertThat(theme.get("worldPalettes").get("math").get("soft").asText()).isEqualTo("#EAF4FF");
        assertThat(theme.get("appName").isNull()).isTrue();
        mvc.perform(get("/schools/no-such-school/theme")).andExpect(status().isNotFound());
    }

    @Test void the_public_theme_is_cacheable_and_answers_304() throws Exception {
        var token = adminToken();
        String school = createSchool(token);

        var first = mvc.perform(get("/schools/" + school + "/theme")).andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("max-age=300, public");

        var notModified = mvc.perform(get("/schools/" + school + "/theme").header("If-None-Match", etag))
                .andExpect(status().isNotModified()).andReturn();
        assertThat(notModified.getResponse().getContentAsString()).isEmpty();
        assertThat(notModified.getResponse().getHeader("ETag")).isEqualTo(etag);
        // a weak validator and a list are both honoured, as RFC 9110 asks
        mvc.perform(get("/schools/" + school + "/theme").header("If-None-Match", "\"stale\", W/" + etag)).andExpect(status().isNotModified());

        saveTheme(token, school, goodTheme("Al Noor Learning"));
        var after = mvc.perform(get("/schools/" + school + "/theme")).andExpect(status().isOk()).andReturn();
        assertThat(after.getResponse().getHeader("ETag")).isNotEqualTo(etag);
        assertThat(json(after).get("appName").asText()).isEqualTo("Al Noor Learning");
        assertThat(json(after).get("fontChoice").asText()).isEqualTo("baloo");
    }

    @Test void a_pair_below_the_threshold_is_refused_by_name_and_ratio() throws Exception {
        var token = adminToken();
        String school = createSchool(token);

        // white ink on a white surface: the first pair checked, primaryInk on primary
        var refused = putTheme(token, school, goodTheme("X").replace("\"primaryInk\":\"#1A1A1A\"", "\"primaryInk\":\"#9A9A9A\""));
        assertThat(refused.get("code").asText()).isEqualTo("bad_request");
        assertThat(refused.get("message").asText()).isEqualTo("primaryInk on primary is 2.8:1, needs 4.5:1");

        // a brand red that is readable on white but not on the ground: accent on ground
        var accent = putTheme(token, school, goodTheme("X").replace("\"accent\":\"#0B5D2E\"", "\"accent\":\"#EC3013\""));
        assertThat(accent.get("message").asText()).isEqualTo("accent on ground is 3.8:1, needs 4.5:1");

        // the mascot on the ground — a graphic, so the bar is WCAG 1.4.11's 3:1 and the message says so
        var mascot = putTheme(token, school, goodTheme("X").replace("\"mascotColor\":\"#2F5D7C\"", "\"mascotColor\":\"#7EC8FF\""));
        assertThat(mascot.get("message").asText()).isEqualTo("mascotColor on ground is 1.6:1, needs 3.0:1");

        // a world's own ink on its own ground
        var world = putTheme(token, school, goodTheme("X").replace("\"soft\":\"#EAF4FF\",\"ink\":\"#1A1A1A\"", "\"soft\":\"#EAF4FF\",\"ink\":\"#9FD4FF\""));
        assertThat(world.get("message").asText()).startsWith("math.ink on math.soft is ").endsWith(", needs 4.5:1");

        // nothing was stored by any of those attempts
        assertThat(json(mvc.perform(get("/schools/" + school + "/theme")).andReturn()).get("appName").isNull()).isTrue();
    }

    /** The mascot may sit between the two bars: readable as a shape (≥ 3:1) without being dark enough for text. */
    @Test void a_mascot_above_three_to_one_but_below_four_and_a_half_is_accepted() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        saveTheme(token, school, goodTheme("Mascot").replace("\"mascotColor\":\"#2F5D7C\"", "\"mascotColor\":\"#598FB8\""));

        var stored = json(mvc.perform(get("/schools/" + school + "/theme")).andExpect(status().isOk()).andReturn());
        assertThat(stored.get("mascotColor").asText()).isEqualTo("#598FB8");
        double ratio = Contrast.ratio("#598FB8", "#F4F4F2");
        assertThat(ratio).isGreaterThanOrEqualTo(Contrast.MINIMUM_NON_TEXT).isLessThan(Contrast.MINIMUM);
        // …and the same colour used as text would still be refused
        assertThat(putTheme(token, school, goodTheme("X").replace("\"accent\":\"#0B5D2E\"", "\"accent\":\"#598FB8\""))
                .get("message").asText()).startsWith("accent on ground is ").endsWith(", needs 4.5:1");
    }

    @Test void colours_must_be_six_digit_hex_and_the_font_one_of_three() throws Exception {
        var token = adminToken();
        String school = createSchool(token);

        assertThat(putTheme(token, school, goodTheme("X").replace("\"ground\":\"#F4F4F2\"", "\"ground\":\"whitish\"")).get("message").asText())
                .isEqualTo("ground must be a colour like #RRGGBB, not whitish");
        assertThat(putTheme(token, school, goodTheme("X").replace("\"primary\":\"#FFFFFF\"", "\"primary\":\"#FFF\"")).get("message").asText())
                .startsWith("primary must be a colour like #RRGGBB");
        assertThat(putTheme(token, school, goodTheme("X").replace("\"fontChoice\":\"baloo\"", "\"fontChoice\":\"comic\"")).get("code").asText())
                .isEqualTo("bad_request");
        assertThat(putTheme(token, school, goodTheme("X").replace("\"math\":", "\"science\":")).get("message").asText())
                .isEqualTo("worldPalettes has no world science; it is math and english");
    }

    /**
     * `logoUrl` is served by the public theme route and by `JoinSchoolInfo`, and both front-ends put it in an
     * `img src`: a `javascript:` or `data:` value stored here would be XSS in the dashboard and the app.
     */
    @Test void a_logo_url_that_is_not_https_is_refused() throws Exception {
        var token = adminToken();
        String school = createSchool(token);

        for (String hostile : List.of("javascript:alert(document.cookie)", "data:text/html;base64,PHNjcmlwdD4=",
                "http://cdn.test/logo.png", " JavaScript:alert(1)")) {
            var refused = putTheme(token, school, goodTheme("X").replace("https://cdn.test/al-noor.png", hostile));
            assertThat(refused.get("code").asText()).isEqualTo("bad_request");
            assertThat(refused.get("message").asText()).as(hostile).isEqualTo("logoUrl must be an https:// URL");
        }
        assertThat(json(mvc.perform(get("/schools/" + school + "/theme")).andReturn()).get("logoUrl").isNull())
                .as("nothing hostile was stored").isTrue();
    }

    /** The public theme is cached for five minutes; an unbounded logo or name would be a payload on that route. */
    @Test void an_oversized_logo_url_or_app_name_is_refused_and_the_public_body_stays_small() throws Exception {
        var token = adminToken();
        String school = createSchool(token);

        // `@Size` on the body answers first here and `SafeText` behind it; either way it is a 400 naming the field
        var longUrl = putTheme(token, school, goodTheme("X").replace("https://cdn.test/al-noor.png", "https://cdn.test/" + "a".repeat(200_000)));
        assertThat(longUrl.get("message").asText()).startsWith("logoUrl");
        var longName = putTheme(token, school, goodTheme("b".repeat(5_000)));
        assertThat(longName.get("message").asText()).startsWith("appName");
        // control characters would be header injection once appName reaches a mail subject
        assertThat(putTheme(token, school, goodTheme("Al Noor\\nBcc: someone@evil.test")).get("message").asText())
                .isEqualTo("appName must not contain control characters");

        saveTheme(token, school, goodTheme("Al Noor Learning"));
        var body = mvc.perform(get("/schools/" + school + "/theme")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(body.length()).as("a themed school's public body is a few hundred bytes, not a few hundred kilobytes").isLessThan(2_048);
    }

    @Test void the_theme_travels_with_the_join_code_and_the_admin_route_is_scoped() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        saveTheme(token, school, goodTheme("Al Noor Learning"));
        String code = json(mvc.perform(admin(get("/admin/schools/" + school), token)).andReturn()).get("code").asText();

        var joined = json(mvc.perform(get("/schools/by-code/" + code)).andExpect(status().isOk()).andReturn());
        assertThat(joined.get("logoUrl").asText()).isEqualTo("https://cdn.test/al-noor.png");
        assertThat(joined.get("theme").get("accent").asText()).isEqualTo("#0B5D2E");
        assertThat(joined.get("theme").get("appName").asText()).isEqualTo("Al Noor Learning");

        var adminView = json(mvc.perform(admin(get("/admin/schools/" + school + "/theme"), token)).andExpect(status().isOk()).andReturn());
        assertThat(adminView.get("accent").asText()).isEqualTo("#0B5D2E");
    }

    private String createSchool(String token) throws Exception {
        var body = "{\"name\":\"Theme School " + UUID.randomUUID().toString().substring(0, 6) + "\",\"curriculumOptions\":[\"british\"],\"gradeOptions\":[1,2,3]}";
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON).content(body), token))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
    }

    private void saveTheme(String token, String school, String theme) throws Exception {
        mvc.perform(admin(put("/admin/schools/" + school + "/theme").contentType(MediaType.APPLICATION_JSON).content(theme), token))
                .andExpect(status().isOk());
    }

    private com.fasterxml.jackson.databind.JsonNode putTheme(String token, String school, String theme) throws Exception {
        return json(mvc.perform(admin(put("/admin/schools/" + school + "/theme").contentType(MediaType.APPLICATION_JSON).content(theme), token))
                .andExpect(status().isBadRequest()).andReturn());
    }
}
