package quest.server.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;
import quest.server.tenancy.SchoolRepository;

/** §4 Admin screen: the matrix, one cell, a whole column, and the trail each flip leaves. */
class FlagAdminTest extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired FeatureFlagRepository definitions;

    @Test void the_fourteen_flags_are_seeded_exactly_as_the_code_lists_them() {
        assertThat(definitions.findAllByOrderByKeyAsc()).extracting(Entities.FeatureFlagEntity::getKey)
                .containsExactlyInAnyOrderElementsOf(FlagKeys.ALL);
        assertThat(FlagKeys.ALL).hasSize(14);

        var on = definitions.findAllByOrderByKeyAsc().stream().filter(Entities.FeatureFlagEntity::isDefaultOn)
                .map(Entities.FeatureFlagEntity::getKey).toList();
        // on: what ships today; off: what phases 4–6 still have to build
        assertThat(on).containsExactlyInAnyOrder(FlagKeys.LESSONS_PDF, FlagKeys.LESSONS_SLIDES, FlagKeys.LESSONS_IMAGES,
                FlagKeys.LESSONS_MANUAL, FlagKeys.LEVELS_THREE, FlagKeys.RETELL_RECORDING, FlagKeys.OPEN_ANSWER_DRAWING,
                FlagKeys.PARENT_PANEL_ARABIC, FlagKeys.STICKERS_TREASURE_CHEST, FlagKeys.CERTIFICATES);
        assertThat(definitions.findAllByOrderByKeyAsc()).filteredOn(f -> !f.isDefaultOn())
                .extracting(Entities.FeatureFlagEntity::getKey)
                .containsExactlyInAnyOrder(FlagKeys.COMPLAINTS, FlagKeys.ANNOUNCEMENTS, FlagKeys.TEACHER_QUESTIONS, FlagKeys.PROGRESS_WEEKLY_EMAIL);
        assertThat(definitions.findAllByOrderByKeyAsc()).allSatisfy(f ->
                assertThat(f.getRolloutStage()).isIn("internal", "beta", "ga"));
    }

    /**
     * shared-api's `DEFAULT_FLAGS` is what `ContentApi.schoolFlags` answers without a backend (a `FakeContentApi`,
     * an offline app), so it has to be the seeded defaults — the same binding `DefaultThemeTest` puts on
     * `SchoolTheme()`, and the only thing stopping the two copies drifting.
     */
    @Test void the_shared_api_defaults_are_the_seeded_defaults() {
        var shared = quest.api.ContentApiKt.getDEFAULT_FLAGS();
        var seeded = definitions.findAllByOrderByKeyAsc().stream()
                .collect(java.util.stream.Collectors.toMap(Entities.FeatureFlagEntity::getKey, Entities.FeatureFlagEntity::isDefaultOn));
        assertThat(shared).as("shared-api/src/commonMain/kotlin/quest/api/ContentApi.kt must match V5__flags_themes.sql")
                .containsExactlyInAnyOrderEntriesOf(seeded);
    }

    @Test void the_public_route_answers_all_fourteen_with_an_etag() throws Exception {
        var token = adminToken();
        String school = createSchool(token);

        var first = mvc.perform(get("/schools/" + school + "/flags")).andExpect(status().isOk()).andReturn();
        var body = json(first);
        assertThat(fieldNames(body)).containsExactlyInAnyOrderElementsOf(FlagKeys.ALL);
        assertThat(body.get(FlagKeys.CERTIFICATES).asBoolean()).isTrue();
        assertThat(body.get(FlagKeys.COMPLAINTS).asBoolean()).isFalse();

        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        assertThat(first.getResponse().getHeader("Cache-Control")).contains("max-age=300").contains("public");
        mvc.perform(get("/schools/" + school + "/flags").header("If-None-Match", etag)).andExpect(status().isNotModified());

        // a flip changes the body, so it changes the ETag: the app's cached copy is invalidated by content
        setFlag(token, school, FlagKeys.COMPLAINTS, true);
        var second = mvc.perform(get("/schools/" + school + "/flags")).andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getHeader("ETag")).isNotEqualTo(etag);
        assertThat(json(second).get(FlagKeys.COMPLAINTS).asBoolean()).isTrue();

        mvc.perform(get("/schools/no-such-school/flags")).andExpect(status().isNotFound());
    }

    @Test void one_cell_changes_one_school_only() throws Exception {
        var token = adminToken();
        String a = createSchool(token), b = createSchool(token);

        setFlag(token, a, FlagKeys.ANNOUNCEMENTS, true);
        assertThat(json(mvc.perform(get("/schools/" + a + "/flags")).andReturn()).get(FlagKeys.ANNOUNCEMENTS).asBoolean()).isTrue();
        assertThat(json(mvc.perform(get("/schools/" + b + "/flags")).andReturn()).get(FlagKeys.ANNOUNCEMENTS).asBoolean()).isFalse();

        var matrix = json(mvc.perform(admin(get("/admin/flags"), token)).andExpect(status().isOk()).andReturn());
        assertThat(matrix.get("definitions")).hasSize(14);
        assertThat(matrix.get("definitions").get(0).get("rolloutStage").asText()).isIn("internal", "beta", "ga");
        assertThat(rowFor(matrix, a).get("flags").get(FlagKeys.ANNOUNCEMENTS).asBoolean()).isTrue();
        assertThat(rowFor(matrix, b).get("flags").get(FlagKeys.ANNOUNCEMENTS).asBoolean()).isFalse();

        mvc.perform(admin(put("/admin/schools/" + a + "/flags/no.such.flag").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"), token)).andExpect(status().isNotFound());
    }

    @Test void the_column_action_flips_every_school_and_is_audited_once() throws Exception {
        var token = adminToken();
        String a = createSchool(token), b = createSchool(token);

        var before = json(mvc.perform(admin(get("/admin/flags/audit?limit=200"), token)).andExpect(status().isOk()).andReturn()).size();

        var matrix = json(mvc.perform(admin(put("/admin/flags/" + FlagKeys.TEACHER_QUESTIONS + "/all")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"), token)).andExpect(status().isOk()).andReturn());
        assertThat(matrix.get("schools")).isNotEmpty();
        matrix.get("schools").forEach(row -> assertThat(row.get("flags").get(FlagKeys.TEACHER_QUESTIONS).asBoolean()).isTrue());
        assertThat(json(mvc.perform(get("/schools/" + a + "/flags")).andReturn()).get(FlagKeys.TEACHER_QUESTIONS).asBoolean()).isTrue();
        assertThat(json(mvc.perform(get("/schools/" + b + "/flags")).andReturn()).get(FlagKeys.TEACHER_QUESTIONS).asBoolean()).isTrue();

        var audit = json(mvc.perform(admin(get("/admin/flags/audit?limit=200"), token)).andExpect(status().isOk()).andReturn());
        assertThat(audit.size()).isEqualTo(before + 1);                 // the column action is one event, not one per school
        var newest = audit.get(0);
        assertThat(newest.get("flagKey").asText()).isEqualTo(FlagKeys.TEACHER_QUESTIONS);
        assertThat(newest.get("schoolId").isNull()).as("null schoolId means every school").isTrue();
        assertThat(newest.get("enabled").asBoolean()).isTrue();
        assertThat(newest.get("actorEmail").asText()).isEqualTo("admin@test.local");

        // …while a single cell is recorded against its school
        setFlag(token, a, FlagKeys.PROGRESS_WEEKLY_EMAIL, true);
        var perSchool = json(mvc.perform(admin(get("/admin/flags/audit?limit=5"), token)).andReturn()).get(0);
        assertThat(perSchool.get("flagKey").asText()).isEqualTo(FlagKeys.PROGRESS_WEEKLY_EMAIL);
        assertThat(perSchool.get("schoolId").asText()).isEqualTo(a);
        assertThat(perSchool.get("schoolName").asText()).isEqualTo(schools.findById(a).orElseThrow().getName());

        assertThat(json(mvc.perform(admin(get("/admin/flags/audit?limit=1"), token)).andReturn()).size()).isEqualTo(1);
    }

    /** §4/§5: `flag.read` reaches MANAGERIAL, but only for her own school — and never `flag.write`. */
    @Test void a_managerial_user_reads_her_own_schools_column_and_no_others() throws Exception {
        var token = adminToken();
        String mine = createSchool(token), other = createSchool(token);
        setFlag(token, other, FlagKeys.ANNOUNCEMENTS, true);

        String email = "flags-" + UUID.randomUUID().toString().substring(0, 8) + "@school.test";
        mvc.perform(admin(post("/admin/schools/" + mine + "/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"MANAGERIAL\",\"password\":\"handed-over-1234\"}"), token))
                .andExpect(status().isCreated());
        String hers = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"handed-over-1234\"}")).andExpect(status().isOk()).andReturn()).get("token").asText();

        var matrix = json(mvc.perform(admin(get("/admin/flags"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(matrix.get("definitions")).hasSize(14);
        assertThat(matrix.get("schools")).hasSize(1);
        assertThat(matrix.get("schools").get(0).get("schoolId").asText()).isEqualTo(mine);

        // …and even pointing the school switcher at another school does not widen it: the scope is her token
        var switched = json(mvc.perform(admin(get("/admin/flags").header(quest.server.tenancy.TenantContext.HEADER, mine), hers)).andReturn());
        assertThat(switched.get("schools")).hasSize(1);

        // writing is ADMIN only
        mvc.perform(admin(put("/admin/schools/" + mine + "/flags/" + FlagKeys.ANNOUNCEMENTS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"), hers)).andExpect(status().isForbidden());
        mvc.perform(admin(put("/admin/flags/" + FlagKeys.ANNOUNCEMENTS + "/all").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"), hers)).andExpect(status().isForbidden());
    }

    private JsonNode rowFor(JsonNode matrix, String schoolId) {
        for (JsonNode row : matrix.get("schools")) if (schoolId.equals(row.get("schoolId").asText())) return row;
        throw new AssertionError("no matrix row for " + schoolId);
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String createSchool(String token) throws Exception {
        var body = "{\"name\":\"Matrix School " + UUID.randomUUID().toString().substring(0, 6) + "\",\"curriculumOptions\":[\"british\"],\"gradeOptions\":[1,2,3]}";
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON).content(body), token))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
    }

    private void setFlag(String token, String school, String key, boolean enabled) throws Exception {
        mvc.perform(admin(put("/admin/schools/" + school + "/flags/" + key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), token)).andExpect(status().isOk());
    }
}
