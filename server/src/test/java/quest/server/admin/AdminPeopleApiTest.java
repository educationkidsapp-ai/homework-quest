package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.ClassFixtures;
import quest.server.auth.Entities.ParentEntity;
import quest.server.auth.ParentRepository;
import quest.server.children.ChildRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TeachingAssignmentRepository;
import quest.server.tenancy.TenantContext;

/**
 * MA1, the owner's admin-role list of 2026-09-30: the Home's six counts, the Workers page, the edit and the new
 * password a coordinator and a manager now have like a teacher, and a child created together with her parent's login.
 *
 * <p>Two schools of this test's own, because half of what MA1 promises is a refusal across the boundary: another
 * school's worker is a 404, and an address that already belongs to another school's family is a 409 rather than a
 * parent shared between two schools. Everything is reached with `X-School-Id`, as the Admin's own dashboard does.
 *
 * <p>The parent accounts are the fake {@link quest.server.auth.ParentAccounts} the test profile wires
 * (`quest.auth.fake=true`), which is the point of the port: no Firebase project, no network call, and the same
 * contract the Firebase implementation has.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminPeopleApiTest extends ApiTestSupport {
    private static final String SCHOOL = "ma1-school", OTHER = "ma1-other";
    private static final String CLASS_A = "ma1-1a", CLASS_B = "ma1-1b", CLASS_OTHER = "ma1-other-1a";

    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired ChildRepository children;
    @Autowired ParentRepository parents;

    @BeforeAll void fixture() {
        school(SCHOOL, "MA1 School");
        school(OTHER, "MA1 Other School");
        ClassFixtures.section(classes, assignments, CLASS_A, SCHOOL, "british", 1, "math", null);
        ClassFixtures.section(classes, assignments, CLASS_B, SCHOOL, "american", 2, "english", null);
        ClassFixtures.section(classes, assignments, CLASS_OTHER, OTHER, "british", 1, "math", null);
    }

    // ---------------------------------------------------------------- the Home's counts (item 1)

    @Test void the_admin_home_counts_managers_coordinators_teachers_children_classes_and_workers() throws Exception {
        String admin = adminToken();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        long classesBefore = card(home(admin), "classes");
        long workersBefore = card(home(admin), "workers");
        long managersBefore = card(home(admin), "managers");
        long coordinatorsBefore = card(home(admin), "coordinators");

        created(admin, "/admin/managers", "{\"fullName\":\"Home Manager\",\"email\":\"home.mgr." + suffix
                + "@ma1.test\",\"curriculum\":\"british\"}");
        created(admin, "/admin/coordinators", "{\"fullName\":\"Home Coordinator\",\"email\":\"home.coord." + suffix
                + "@ma1.test\",\"scopes\":[{\"subject\":\"math\"}]}");
        created(admin, "/admin/workers", "{\"fullName\":\"Home Caretaker\",\"job\":\"caretaker\",\"phone\":\"0501002030\"}");

        var after = home(admin);
        assertThat(card(after, "managers")).isEqualTo(managersBefore + 1);
        assertThat(card(after, "coordinators")).isEqualTo(coordinatorsBefore + 1);
        assertThat(card(after, "workers")).isEqualTo(workersBefore + 1);
        // The three sections this class seeded are in the scoped school's count and nothing else changed them.
        assertThat(card(after, "classes")).isEqualTo(classesBefore);
        assertThat(card(after, "teachers")).isNotNegative();
        assertThat(card(after, "children")).isNotNegative();
    }


    /**
     * The `classes` card counts what `GET /admin/classes` lists and nothing else. A row with no `name` is one V7
     * backfilled from a pre-section `classes` row: it is not a section anybody can open, the page does not list it,
     * and counting it made the card read higher than the page it sends her to — on `default`, which is the school QA
     * runs on.
     */
    @Test void the_classes_card_excludes_a_row_v7_left_without_a_name() throws Exception {
        String admin = adminToken();
        long before = card(home(admin), "classes");
        assertThat(json(mvc.perform(scoped(get("/admin/classes"), admin, SCHOOL)).andExpect(status().isOk()).andReturn()))
                .hasSize((int) before);                                       // the card and the page agree to start with

        var legacy = new quest.server.tenancy.Entities.ClassEntity();
        legacy.setId("ma1-legacy"); legacy.setSchoolId(SCHOOL); legacy.setCurriculum("british"); legacy.setGrade(3);
        legacy.setSubject("math"); legacy.setName(null); legacy.setActive(true); legacy.setJoinCodeEnabled(true);
        legacy.setCreatedAt(Instant.now());
        classes.save(legacy);
        try {
            assertThat(card(home(admin), "classes")).isEqualTo(before);
            assertThat(json(mvc.perform(scoped(get("/admin/classes"), admin, SCHOOL)).andReturn())).hasSize((int) before);
        } finally {
            classes.deleteById(legacy.getId());
        }
    }

    // ---------------------------------------------------------------- the Workers page (item 4)

    @Test void a_worker_is_created_edited_retired_and_invisible_to_another_school() throws Exception {
        String admin = adminToken();
        var created = json(mvc.perform(scoped(post("/admin/workers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\" Ahmed Driver \",\"job\":\"driver\",\"phone\":\"050 100 2030\"}"), admin, SCHOOL))
                .andExpect(status().isCreated()).andReturn());
        String id = created.get("id").asText();
        assertThat(created.get("fullName").asText()).isEqualTo("Ahmed Driver");                 // trimmed
        assertThat(created.get("phone").asText()).isEqualTo("0501002030");                      // normalised (MH1)
        assertThat(created.get("active").asBoolean()).isTrue();

        var edited = json(mvc.perform(scoped(patch("/admin/workers/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":\"bus supervisor\",\"phone\":\"+971 50 1112233\"}"), admin, SCHOOL)).andExpect(status().isOk()).andReturn());
        assertThat(edited.get("job").asText()).isEqualTo("bus supervisor");
        assertThat(edited.get("phone").asText()).isEqualTo("+971501112233");

        // Another school's Admin scope cannot see her, edit her or retire her: the lookup is a filtered query.
        mvc.perform(scoped(get("/admin/workers"), admin, OTHER)).andExpect(status().isOk());
        assertThat(ids(json(mvc.perform(scoped(get("/admin/workers"), admin, OTHER)).andReturn()), "id")).doesNotContain(id);
        mvc.perform(scoped(patch("/admin/workers/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":\"nurse\"}"), admin, OTHER)).andExpect(status().isNotFound());
        mvc.perform(scoped(delete("/admin/workers/" + id), admin, OTHER)).andExpect(status().isNotFound());

        mvc.perform(scoped(delete("/admin/workers/" + id), admin, SCHOOL)).andExpect(status().isNoContent());
        assertThat(json(mvc.perform(scoped(get("/admin/workers"), admin, SCHOOL)).andReturn()))
                .anySatisfy(row -> { if (id.equals(row.get("id").asText())) assertThat(row.get("active").asBoolean()).isFalse(); });

        // A blank name, a name nobody could have typed and a telephone number that is not one are all 400s.
        for (String body : List.of("{\"fullName\":\"  \",\"job\":\"nurse\"}", "{\"fullName\":\"A\",\"job\":\" \"}",
                "{\"fullName\":\"A\",\"job\":\"nurse\",\"phone\":\"12\"}"))
            mvc.perform(scoped(post("/admin/workers").contentType(MediaType.APPLICATION_JSON).content(body), admin, SCHOOL))
                    .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- coordinators and managers (item 3)

    @Test void a_coordinator_and_a_manager_are_edited_and_given_a_new_password_like_a_teacher() throws Exception {
        String admin = adminToken();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String coordinator = created(admin, "/admin/coordinators", "{\"fullName\":\"Lina Edit\",\"email\":\"lina.edit." + suffix
                + "@ma1.test\",\"scopes\":[{\"subject\":\"math\",\"curriculum\":\"british\"}]}").get("coordinator").get("userId").asText();
        String manager = created(admin, "/admin/managers", "{\"fullName\":\"Noura Edit\",\"email\":\"noura.edit." + suffix
                + "@ma1.test\",\"curriculum\":\"british\"}").get("manager").get("userId").asText();

        var editedCoordinator = json(mvc.perform(scoped(patch("/admin/coordinators/" + coordinator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Lina Renamed\",\"phone\":\"0501002030\",\"active\":false}"), admin, SCHOOL))
                .andExpect(status().isOk()).andReturn());
        assertThat(editedCoordinator.get("fullName").asText()).isEqualTo("Lina Renamed");
        assertThat(editedCoordinator.get("phone").asText()).isEqualTo("0501002030");
        assertThat(editedCoordinator.get("status").asText()).isEqualTo("disabled");
        // The PATCH did not touch her scope, which is the other route's.
        assertThat(editedCoordinator.get("scopes")).hasSize(1);

        var editedManager = json(mvc.perform(scoped(patch("/admin/managers/" + manager)
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Noura Renamed\",\"active\":false}"), admin, SCHOOL))
                .andExpect(status().isOk()).andReturn());
        assertThat(editedManager.get("fullName").asText()).isEqualTo("Noura Renamed");
        assertThat(editedManager.get("status").asText()).isEqualTo("disabled");
        assertThat(ids(editedManager.get("departments"), null)).containsExactly("british");

        for (String path : List.of("/admin/coordinators/" + coordinator, "/admin/managers/" + manager))
            assertThat(json(mvc.perform(scoped(post(path + "/reset-password"), admin, SCHOOL)).andExpect(status().isOk()).andReturn())
                    .get("temporaryPassword").asText()).isNotBlank();

        // The wrong role behind the id is a 404 on both routes, as it is on their `scopes` twins.
        mvc.perform(scoped(patch("/admin/coordinators/" + manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"No\"}"), admin, SCHOOL)).andExpect(status().isNotFound());
        mvc.perform(scoped(post("/admin/managers/" + coordinator + "/reset-password"), admin, SCHOOL))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- children and parents (item 5)

    @Test void a_child_is_created_with_a_new_parent_account_and_appears_on_the_roster_and_the_page() throws Exception {
        String admin = adminToken();
        String email = "family." + UUID.randomUUID().toString().substring(0, 6) + "@ma1.test";
        var admitted = json(mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Hala Ahmed", CLASS_A, 1, "british", "Ahmed Ali", email, "0501002030", "read-it-out")), admin, SCHOOL))
                .andExpect(status().isCreated()).andReturn());
        assertThat(admitted.get("parentCreated").asBoolean()).isTrue();
        String childId = admitted.get("childId").asText(), parentId = admitted.get("parentId").asText();

        // The placement is the roster's, read by the route the Admin's class screen reads.
        assertThat(ids(json(mvc.perform(scoped(get("/admin/classes/" + CLASS_A + "/children"), admin, SCHOOL))
                .andExpect(status().isOk()).andReturn()), "id")).contains(childId);

        var page = json(mvc.perform(scoped(get("/admin/children/search?q=Hala"), admin, SCHOOL)).andExpect(status().isOk()).andReturn());
        assertThat(page.get("total").asInt()).isPositive();
        var row = row(page, childId);
        assertThat(row.get("className").asText()).isEqualTo(classes.findById(CLASS_A).orElseThrow().getName());
        assertThat(row.get("grade").asInt()).isEqualTo(1);
        assertThat(row.get("curriculum").asText()).isEqualTo("british");
        assertThat(row.get("parentName").asText()).isEqualTo("Ahmed Ali");
        assertThat(row.get("parentEmail").asText()).isEqualTo(email);
        assertThat(row.get("parentPhone").asText()).isEqualTo("0501002030");
        // Searching by the parent's telephone number, by her name alone ("Ali" is hers and not the child's) and by
        // her address finds the same row — the three columns V25 and MH1 added to this page.
        for (String q : List.of("0501002030", "Ali", email))
            assertThat(json(mvc.perform(scoped(get("/admin/children/search?q=" + q), admin, SCHOOL)).andReturn()).get("total").asInt())
                    .describedAs("searching for %s", q).isPositive();

        // A second child of the same family reuses the account; `parentCreated` says so.
        var sibling = json(mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Omar Ahmed", CLASS_B, 2, "american", "Ahmed Ali", email, null, "read-it-out")), admin, SCHOOL))
                .andExpect(status().isCreated()).andReturn());
        assertThat(sibling.get("parentCreated").asBoolean()).isFalse();
        assertThat(sibling.get("parentId").asText()).isEqualTo(parentId);

        // The parent's password is replaced through the port, answered once.
        assertThat(json(mvc.perform(scoped(post("/admin/children/" + childId + "/parent/reset-password"), admin, SCHOOL))
                .andExpect(status().isOk()).andReturn()).get("temporaryPassword").asText()).isNotBlank();

        // `PATCH /admin/children/{id}` now carries the parent's number (MA1 widens MH1's column onto this screen).
        mvc.perform(scoped(patch("/admin/children/" + childId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentPhone\":\"+971 50 111 2233\"}"), admin, SCHOOL)).andExpect(status().isOk());
        assertThat(parents.findById(parentId).orElseThrow().getPhone()).isEqualTo("+971501112233");
    }

    @Test void an_address_that_belongs_to_another_school_is_refused_and_a_short_password_is_refused() throws Exception {
        String admin = adminToken();
        // A family of the other school, written the way `POST /admin/children` would have written it there.
        String email = "elsewhere." + UUID.randomUUID().toString().substring(0, 6) + "@ma1.test";
        mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Rana Other", CLASS_OTHER, 1, "british", "Other Parent", email, null, "read-it-out")), admin, OTHER))
                .andExpect(status().isCreated());

        mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Rana Here", CLASS_A, 1, "british", "Other Parent", email, null, "read-it-out")), admin, SCHOOL))
                .andExpect(status().isConflict());

        // Seven characters is not enough, a grade or a track the section does not have is a 400, and another
        // school's section is a 404 — all before anything is written.
        mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Short Password", CLASS_A, 1, "british", "P", "short." + email, null, "1234567")), admin, SCHOOL))
                .andExpect(status().isBadRequest());
        mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Wrong Grade", CLASS_A, 5, "british", "P", "grade." + email, null, "read-it-out")), admin, SCHOOL))
                .andExpect(status().isBadRequest());
        mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Wrong Track", CLASS_A, 1, "american", "P", "track." + email, null, "read-it-out")), admin, SCHOOL))
                .andExpect(status().isBadRequest());
        mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("Elsewhere", CLASS_OTHER, 1, "british", "P", "where." + email, null, "read-it-out")), admin, SCHOOL))
                .andExpect(status().isNotFound());
        // …and the other school's children are not on this school's page.
        assertThat(json(mvc.perform(scoped(get("/admin/children/search?q=Rana%20Other"), admin, SCHOOL)).andReturn())
                .get("total").asInt()).isZero();
    }

    @Test void a_parent_account_the_app_created_keeps_its_uid_and_is_reused() throws Exception {
        String admin = adminToken();
        // A `parents` row as `FirebaseTokenFilter` writes one on first sight of a token: a uid, an address, no name.
        String email = "app." + UUID.randomUUID().toString().substring(0, 6) + "@ma1.test";
        var existing = new ParentEntity();
        existing.setId(UUID.randomUUID().toString()); existing.setFirebaseUid("fake-token-" + email);
        existing.setEmail(email); existing.setCreatedAt(Instant.now());
        parents.save(existing);

        var admitted = json(mvc.perform(scoped(post("/admin/children").contentType(MediaType.APPLICATION_JSON)
                        .content(admission("App Child", CLASS_A, 1, "british", "App Parent", email, "0501002030", "read-it-out")), admin, SCHOOL))
                .andExpect(status().isCreated()).andReturn());
        assertThat(admitted.get("parentId").asText()).isEqualTo(existing.getId());
        assertThat(admitted.get("parentCreated").asBoolean()).isFalse();
        var reloaded = parents.findById(existing.getId()).orElseThrow();
        assertThat(reloaded.getFirebaseUid()).isEqualTo("fake-token-" + email);       // the app's uid, untouched
        assertThat(reloaded.getDisplayName()).isEqualTo("App Parent");                // V25's column, filled in
        assertThat(children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(existing.getId())).isNotEmpty();
    }

    // ---------------------------------------------------------------- fixture helpers

    private static String admission(String name, String classId, int grade, String curriculum, String parentName,
                                    String parentEmail, String parentPhone, String password) {
        return "{\"name\":\"" + name + "\",\"grade\":" + grade + ",\"curriculum\":\"" + curriculum + "\",\"classId\":\"" + classId
                + "\",\"parentName\":\"" + parentName + "\",\"parentEmail\":\"" + parentEmail + "\""
                + (parentPhone == null ? "" : ",\"parentPhone\":\"" + parentPhone + "\"")
                + ",\"parentInitialPassword\":\"" + password + "\"}";
    }

    private void school(String id, String name) {
        if (schools.findById(id).isPresent()) return;
        var s = new SchoolEntity();
        s.setId(id); s.setName(name); s.setCode("MA1" + id.hashCode()); s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private JsonNode created(String token, String path, String body) throws Exception {
        return json(mvc.perform(scoped(post(path).contentType(MediaType.APPLICATION_JSON).content(body), token, SCHOOL))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode home(String token) throws Exception {
        return json(mvc.perform(scoped(get("/me/home"), token, SCHOOL)).andExpect(status().isOk()).andReturn());
    }

    private static long card(JsonNode home, String key) {
        for (var card : home.get("cards")) if (key.equals(card.get("key").asText())) return card.get("value").asLong();
        throw new AssertionError(key + " is not a card of " + home.get("cards"));
    }

    private static JsonNode row(JsonNode page, String childId) {
        for (var row : page.get("rows")) if (childId.equals(row.get("childId").asText())) return row;
        throw new AssertionError(childId + " is not on " + page.get("rows"));
    }

    /** The values of `field` in an array node, or the array's own texts when `field` is null. */
    private static List<String> ids(JsonNode array, String field) {
        var out = new java.util.ArrayList<String>();
        for (var node : array) out.add(field == null ? node.asText() : node.get(field).asText());
        return out;
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return builder.header("Authorization", "Bearer " + token).header(TenantContext.HEADER, schoolId);
    }
}
