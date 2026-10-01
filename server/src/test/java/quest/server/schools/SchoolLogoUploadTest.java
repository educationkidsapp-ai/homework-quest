package quest.server.schools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * S1: the school logo as an upload. The Admin puts an image on the school; it becomes the theme's `logoUrl` on every
 * read — the public theme, the Admin's, `/me/home` — and is served by a public, cacheable, id-addressed GET that can
 * only ever answer an image. Only `school.write` reaches the two writes.
 */
class SchoolLogoUploadTest extends ApiTestSupport {
    private static final String SCHOOL = "logoup-school", OTHER = "logoup-other";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired SchoolRepository schools;
    @Autowired AdminJwtService jwt;

    @BeforeEach void seed() { school(SCHOOL, "LGUP01"); school(OTHER, "LGUP02"); }

    @Test void the_admin_uploads_a_logo_and_every_reader_of_the_theme_gets_it() throws Exception {
        String admin = adminToken();
        mvc.perform(get("/schools/" + SCHOOL + "/logo")).andExpect(status().isNotFound());

        var uploaded = json(mvc.perform(as(upload(SCHOOL, new MockMultipartFile("file", "crest.png", "image/png", PNG)), admin))
                .andExpect(status().isOk()).andReturn());
        String url = uploaded.get("logoUrl").asText();
        assertThat(url).contains("/schools/" + SCHOOL + "/logo?v=");

        // Public, cacheable, an image and nothing else — with no token at all, as the sign-in page reads it.
        mvc.perform(get("/schools/" + SCHOOL + "/logo")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(PNG));
        mvc.perform(get("/schools/" + OTHER + "/logo")).andExpect(status().isNotFound());

        // The one field every client already reads.
        assertThat(json(mvc.perform(get("/schools/" + SCHOOL + "/theme")).andExpect(status().isOk()).andReturn()).get("logoUrl").asText()).isEqualTo(url);
        var home = json(mvc.perform(as(get("/me/home"), admin).header(TenantContext.HEADER, SCHOOL)).andExpect(status().isOk()).andReturn());
        assertThat(home.get("schoolLogoUrl").asText()).isEqualTo(url);

        // The theme editor sends back what it read: the derived URL is not frozen into the theme, and survives the save.
        var theme = mvc.perform(as(get("/admin/schools/" + SCHOOL + "/theme"), admin)).andReturn().getResponse().getContentAsString();
        var saved = json(mvc.perform(as(put("/admin/schools/" + SCHOOL + "/theme").contentType(MediaType.APPLICATION_JSON).content(theme), admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(saved.get("logoUrl").asText()).isEqualTo(url);
        assertThat(schools.findById(SCHOOL).orElseThrow().getThemeJson()).doesNotContain("/logo?v=");

        // A replacement is a new URL; a delete leaves the school with no logo at all.
        Thread.sleep(5);
        String second = json(mvc.perform(as(upload(SCHOOL, new MockMultipartFile("file", "crest2.png", "image/png", PNG)), admin))
                .andExpect(status().isOk()).andReturn()).get("logoUrl").asText();
        assertThat(second).isNotEqualTo(url);
        mvc.perform(as(delete("/admin/schools/" + SCHOOL + "/logo"), admin)).andExpect(status().isNoContent());
        mvc.perform(get("/schools/" + SCHOOL + "/logo")).andExpect(status().isNotFound());
        assertThat(json(mvc.perform(get("/schools/" + SCHOOL + "/theme")).andReturn()).hasNonNull("logoUrl")).isFalse();
    }

    @Test void only_a_small_image_and_only_from_an_admin() throws Exception {
        String admin = adminToken();
        var pdf = new MockMultipartFile("file", "crest.png", "image/png", "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(as(upload(SCHOOL, pdf), admin)).andExpect(status().isBadRequest());
        byte[] big = new byte[(int) SchoolLogoService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        mvc.perform(as(upload(SCHOOL, new MockMultipartFile("file", "big.png", "image/png", big)), admin)).andExpect(status().isPayloadTooLarge());
        mvc.perform(as(upload("logoup-nowhere", new MockMultipartFile("file", "crest.png", "image/png", PNG)), admin)).andExpect(status().isNotFound());

        for (String role : java.util.List.of("TEACHER", "MANAGERIAL")) {
            String staff = jwt.issue("logoup-" + role, role + "@logo.test", role, SCHOOL).token();
            mvc.perform(as(upload(SCHOOL, new MockMultipartFile("file", "crest.png", "image/png", PNG)), staff)).andExpect(status().isForbidden());
            mvc.perform(as(delete("/admin/schools/" + SCHOOL + "/logo"), staff)).andExpect(status().isForbidden());
        }
        mvc.perform(upload(SCHOOL, new MockMultipartFile("file", "crest.png", "image/png", PNG))).andExpect(status().isUnauthorized());
        mvc.perform(get("/schools/" + SCHOOL + "/logo")).andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder upload(String schoolId, MockMultipartFile file) {
        return multipart(org.springframework.http.HttpMethod.PUT, "/admin/schools/" + schoolId + "/logo").file(file);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) { return b.header("Authorization", "Bearer " + token); }

    private void school(String id, String code) {
        var s = schools.findById(id).orElseGet(SchoolEntity::new);
        s.setId(id); s.setName("Logo School"); s.setCode(code); s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1]");
        s.setStatus("active"); s.setThemeJson(null); s.setLogoPath(null); s.setLogoType(null); s.setLogoUpdatedAt(null);
        if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
        schools.save(s);
    }
}
