package quest.server.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.grading.GradingTestSupport;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.StaffScopeRepository;

/**
 * MH1 (the owner's second manager list, items 3–5): <strong>a telephone number on every name</strong>, and the
 * coordinator above each teacher. RM5's directory had to answer "no telephone number is returned because no table
 * holds one"; V24 adds `users.phone` and `parents.phone`, and this is every door they arrive through.
 *
 * <p>Nour runs the British department, Maya teaches maths in it and Lina coordinates maths/British, so Lina is the
 * coordinator `GET /management/teachers` names above Maya. Omar coordinates english/American: the same subject axis
 * one track over, which is the pair that a match on subject alone would get wrong.
 */
class PhoneDirectoryApiTest extends GradingTestSupport {
    private static final String P = "mh1-";
    private static final String SCHOOL = P + "school", CODE = "MH1A01";
    private static final String MAYA = P + "maya", NOUR = P + "nour", LINA = P + "lina", OMAR = P + "omar";

    @Override public String prefix() { return P; }

    @Autowired StaffScopeRepository staffScopes;

    private String nour, lina, admin;
    private ClassEntity british;
    private String hana;

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Phone Academy", CODE);
        teacher(MAYA, SCHOOL, "Ms Maya");
        staff(NOUR, "Nour", "MANAGERIAL"); staff(LINA, "Lina", "COORDINATOR"); staff(OMAR, "Omar", "COORDINATOR");
        scopeRow(P + "dept-british", NOUR, null, "british");
        scopeRow(P + "scope-math", LINA, "math", "british");
        scopeRow(P + "scope-english-us", OMAR, "english", "american");
        british = klass(P + "1a-british", SCHOOL, MAYA, "1A British");
        hana = child("Hana", CODE, british);
        nour = token(NOUR, "MANAGERIAL", SCHOOL); lina = token(LINA, "COORDINATOR", SCHOOL);
        admin = adminToken();
    }

    @AfterEach void clean() {
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getId().startsWith(P)).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- staff

    /**
     * The two doors a member of staff's own number arrives through: she types it into her profile (`PATCH /me`) and
     * the Admin corrects it on her account (`PATCH /admin/users/{id}`). Both land on the manager's screens, which is
     * the whole point of the column.
     */
    @Test void a_teacher_types_her_own_number_and_the_admin_corrects_a_coordinators() throws Exception {
        // As typed, with the separators a person actually uses; stored normalised, because 20 characters is E.164's own
        // maximum and a screen that printed "(050) 100-2030" would have no room for the number underneath it.
        var mine = patched("/me", token(MAYA, "TEACHER", SCHOOL), "{\"phone\":\"+971 (50) 100-2030\"}");
        assertThat(mine.get("phone").asText()).isEqualTo("+971501002030");
        assertThat(json(mvc.perform(as(get("/me"), token(MAYA, "TEACHER", SCHOOL))).andExpect(status().isOk()).andReturn())
                .get("phone").asText()).isEqualTo("+971501002030");

        var hers = patched("/admin/users/" + LINA, admin, "{\"phone\":\"0501002031\"}");
        assertThat(hers.get("phone").asText()).as("a local number keeps no plus it never had").isEqualTo("0501002031");

        assertThat(row(directory("/management/teachers", nour), "userId", MAYA).get("phone").asText()).isEqualTo("+971501002030");
        assertThat(row(directory("/management/coordinators", nour), "userId", LINA).get("phone").asText()).isEqualTo("0501002031");
        // R2's own screen shows it too: the coordinator looking at her teachers needs the same number.
        assertThat(row(directory("/coordinator/teachers", lina), "userId", MAYA).get("phone").asText()).isEqualTo("+971501002030");

        // An empty string is how she takes the number back off the screens; nonsense is a 400, not a stored string.
        assertThat(patched("/me", token(MAYA, "TEACHER", SCHOOL), "{\"phone\":\"\"}").get("phone").isNull()).isTrue();
        for (String bad : List.of("\"12345\"", "\"+9715010020301234567\"", "\"050 ABC 2030\""))
            mvc.perform(as(patch("/me").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":" + bad + "}"),
                    token(MAYA, "TEACHER", SCHOOL))).andExpect(status().isBadRequest());
    }

    /** `POST /admin/teachers` takes the number with the account, so a new teacher is reachable from her first day. */
    @Test void a_teacher_created_by_the_admin_carries_her_number() throws Exception {
        var created = json(mvc.perform(as(post("/admin/teachers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Dana\",\"email\":\"" + P + "dana@seed.test\",\"subjects\":[\"math\"],"
                                + "\"curriculum\":\"british\",\"phone\":\"+971501002040\"}"),
                admin).header(quest.server.tenancy.TenantContext.HEADER, SCHOOL)).andExpect(status().isCreated()).andReturn());
        assertThat(created.get("teacher").get("phone").asText()).isEqualTo("+971501002040");
        users.findById(created.get("teacher").get("userId").asText())
                .ifPresent(u -> assertThat(u.getPhone()).isEqualTo("+971501002040"));
    }

    /**
     * The owner's item 4: "the teacher's coordinator name(s)". A coordinator is above a teacher when her scope row
     * names the subject of one of that teacher's slots <em>and</em> either its track or no track at all — so Omar,
     * who coordinates english in the other department, is on nobody's list here even though english exists in both.
     */
    @Test void the_teachers_row_names_the_coordinators_above_her() throws Exception {
        var maya = row(directory("/management/teachers", nour), "userId", MAYA);
        assertThat(ids(maya.get("coordinators"), "userId")).containsExactly(LINA);
        assertThat(maya.get("coordinators").get(0).get("subject").asText()).isEqualTo("math");
        assertThat(maya.get("coordinators").get(0).get("displayName").asText()).isEqualTo("Lina");
        // The people directory answers the same rows, so the two screens cannot drift apart.
        var paged = json(mvc.perform(as(get("/management/people/teachers"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(ids(row(paged.get("rows"), "userId", MAYA).get("coordinators"), "userId")).containsExactly(LINA);
        // And `/coordinator/teachers` leaves it empty: the reader there is the person this list would name.
        assertThat(row(directory("/coordinator/teachers", lina), "userId", MAYA).get("coordinators")).isEmpty();
    }

    // ---------------------------------------------------------------- the parent

    /**
     * The owner's item 5: the parent's number, and whether there is a parent at all. She sets it herself — nobody
     * else may, and until MH1 she had no route of her own — and it appears on the Children directory beside
     * `parentId`, which is what the "message the parent" button needs to exist.
     */
    @Test void the_parent_sets_her_number_and_the_children_directory_shows_it() throws Exception {
        var before = parentGet("/parent/me");
        assertThat(before.get("phone").isNull()).isTrue();
        assertThat(before.get("email").asText()).isNotBlank();

        var after = patched("/parent/me", null, "{\"phone\":\"+971 55 900 1122\"}");
        assertThat(after.get("phone").asText()).isEqualTo("+971559001122");
        assertThat(after.get("parentId").asText()).isEqualTo(before.get("parentId").asText());

        var child = row(json(mvc.perform(as(get("/management/people/children"), nour)).andExpect(status().isOk()).andReturn())
                .get("rows"), "childId", hana);
        assertThat(child.get("parentPhone").asText()).isEqualTo("+971559001122");
        assertThat(child.get("parentId").asText()).isEqualTo(before.get("parentId").asText());

        // A dashboard user has `/me` and no business in a `parents` row; a parent has no business in `/me`.
        mvc.perform(as(get("/parent/me"), nour)).andExpect(status().isForbidden());
        mvc.perform(get("/me").header("Authorization", PARENT)).andExpect(status().isForbidden());
        mvc.perform(patch("/parent/me").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"nope\"}")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    /** `PATCH` with a dashboard token, or with the suite's parent bearer when `token` is null. */
    private JsonNode patched(String path, String token, String body) throws Exception {
        var request = patch(path).contentType(MediaType.APPLICATION_JSON).content(body);
        return json(mvc.perform(token == null ? request.header("Authorization", PARENT) : as(request, token))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode directory(String path, String token) throws Exception {
        return json(mvc.perform(as(get(path), token)).andExpect(status().isOk()).andReturn());
    }

    private static JsonNode row(JsonNode rows, String field, String value) {
        for (var row : rows) if (value.equals(row.get(field).asText())) return row;
        throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
    }

    private static List<String> ids(JsonNode rows, String field) {
        var out = new ArrayList<String>();
        rows.forEach(row -> out.add(row.get(field).asText()));
        return out;
    }

    private void staff(String id, String displayName, String role) {
        var u = users.findById(id).orElseGet(quest.server.auth.Entities.UserEntity::new);
        u.setId(id); u.setSchoolId(SCHOOL); u.setEmail(id + "@seed.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    private void scopeRow(String id, String userId, String subject, String curriculum) {
        var row = staffScopes.findById(id).orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(id); row.setSchoolId(SCHOOL); row.setUserId(userId);
        row.setSubject(subject); row.setCurriculum(curriculum);
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
    }
}
