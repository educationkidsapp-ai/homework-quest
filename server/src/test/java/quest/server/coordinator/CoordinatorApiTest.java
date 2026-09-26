package quest.server.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.classes.SchoolSeed;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeachingAssignmentRepository;

/**
 * R2 on the owner's own fixture: the `acceptance` seed profile, loaded into a school of this test's own. Maya teaches
 * math in 1A and 1B British, Rami english in 1A American, Lina coordinates math/British and Omar english/American —
 * which is the smallest school in which "her subject, her track, and nobody else's" can be told apart at all.
 *
 * <p>What it proves, in the order DR1/DR2 put it: the role signs in; her scope is what the seed wrote; the three
 * reads answer her two sections and not Rami's one; a both-tracks scope widens to all three; a lesson, a class and a
 * child outside her scope are 403 and another school's is 404; the calendar carries the published lesson; every write
 * the teacher has is refused for the role; and the Admin creates a coordinator and changes her scope.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoordinatorApiTest extends ApiTestSupport {
    private static final String SCHOOL = "coord-school";
    private static final String DIR = "seed/acceptance/";
    private static final String STAFF_PASSWORD = "coord-pass";
    private static final String MAYA = "maya@test.com", RAMI = "rami@test.com";
    private static final String LINA = "coord.math@test.com", OMAR = "coord.english@test.com";

    @Autowired SchoolSeed seed;
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired StaffScopeRepository staffScopes;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired ChildRepository childRows;
    @Autowired LessonRepository lessons;
    @Autowired AdminJwtService jwt;
    @Autowired quest.server.tenancy.CoordinatorScope scope;
    @Autowired quest.server.tenancy.TenantContext tenant;

    /** Maya's published lesson in 1A British, on a day inside the window every calendar assertion asks for. */
    private static final LocalDate LESSON_DAY = LocalDate.of(2026, 3, 4);
    private String lina, omar, britishA, britishB, americanA;

    @BeforeAll void loadTheSchool() {
        schools.findById(SCHOOL).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(SCHOOL); s.setName("Coordinator School"); s.setCode("COORD1");
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
        seed.load(SCHOOL, STAFF_PASSWORD, false, DIR);
        lina = idOf(LINA); omar = idOf(OMAR);
        britishA = sectionId("1A British"); britishB = sectionId("1B British"); americanA = sectionId("1A American");
        lesson("coord-l-math-a", britishA, "british", "math", LESSON_DAY, "published");
        lesson("coord-l-math-b", britishB, "british", "math", LESSON_DAY.plusDays(1), "review");
        lesson("coord-l-eng-a", americanA, "american", "english", LESSON_DAY, "published");
        // An english lesson parked in a section Lina *does* supervise: her track matches and her subject does not, so
        // this is the case where only `requireLesson`'s subject half can refuse it.
        lesson("coord-l-eng-in-british", britishA, "british", "english", LESSON_DAY, "published");
    }

    @AfterAll void takeItBackOut() {
        lessons.deleteAll(lessons.findAll().stream().filter(l -> SCHOOL.equals(l.getSchoolId())).toList());
        childRows.deleteAll(childRows.findAll().stream().filter(c -> SCHOOL.equals(c.getSchoolId())).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> SCHOOL.equals(r.getSchoolId())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())).toList());
        var staff = users.findBySchoolId(SCHOOL);
        teacherProfiles.deleteAll(teacherProfiles.findAllById(staff.stream().map(UserEntity::getId).toList()));
        users.deleteAll(staff);
    }

    // ---------------------------------------------------------------- the role itself

    @Test void the_seeded_coordinator_signs_in_and_carries_her_school() throws Exception {
        var session = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + LINA + "\",\"password\":\"" + STAFF_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(session.get("role").asText()).isEqualTo("COORDINATOR");
        assertThat(session.get("schoolId").asText()).isEqualTo(SCHOOL);
        assertThat(session.get("mustChangePassword").asBoolean()).isFalse();

        // The keys the dashboard reads off `/me/permissions` are the coordinator's reads and nothing that writes.
        var keys = json(mvc.perform(as(get("/me/permissions"), token(lina))).andExpect(status().isOk()).andReturn());
        var granted = new ArrayList<String>();
        keys.get("permissions").forEach(k -> granted.add(k.asText()));
        assertThat(granted).contains("coordinator.read", "coordinator.lesson.read", "me.read")
                .doesNotContain("coordinator.manage", "lesson.write", "teacher.week");
    }

    @Test void her_scope_is_her_subject_and_her_track() throws Exception {
        var me = json(mvc.perform(as(get("/coordinator/me"), token(lina))).andExpect(status().isOk()).andReturn());
        assertThat(me.get("displayName").asText()).isEqualTo("Lina");
        assertThat(scopes(me)).containsExactly("math/british");
        assertThat(me.get("sections").asInt()).isEqualTo(2);
        assertThat(me.get("teachers").asInt()).isEqualTo(1);
        assertThat(me.get("children").asInt()).isZero();                          // the acceptance profile seeds none
    }

    // ---------------------------------------------------------------- what she sees, and what she does not

    @Test void the_math_british_coordinator_sees_mayas_sections_and_not_ramis() throws Exception {
        assertThat(classNames(lina)).containsExactly("1A British", "1B British");
        assertThat(teacherEmails(lina)).containsExactly(MAYA);

        assertThat(classNames(omar)).containsExactly("1A American");
        assertThat(teacherEmails(omar)).containsExactly(RAMI);
    }

    @Test void her_lesson_list_is_her_subject_only() throws Exception {
        assertThat(lessonIds(lina, "")).containsExactlyInAnyOrder("coord-l-math-a", "coord-l-math-b");
        assertThat(lessonIds(omar, "")).containsExactly("coord-l-eng-a");

        // The coarse status filter the grid uses, and the class filter inside her scope.
        assertThat(lessonIds(lina, "?status=published")).containsExactly("coord-l-math-a");
        assertThat(lessonIds(lina, "?status=ready")).containsExactly("coord-l-math-b");
        assertThat(lessonIds(lina, "?classId=" + britishB)).containsExactly("coord-l-math-b");
        mvc.perform(as(get("/coordinator/lessons?status=nonsense"), token(lina))).andExpect(status().isBadRequest());

        var lesson = json(mvc.perform(as(get("/coordinator/lessons/coord-l-math-a"), token(lina)))
                .andExpect(status().isOk()).andReturn());
        assertThat(lesson.get("id").asText()).isEqualTo("coord-l-math-a");
        assertThat(lesson.get("subject").asText()).isEqualTo("math");
    }

    @Test void a_lesson_a_class_and_a_child_outside_her_scope_are_refused() throws Exception {
        mvc.perform(as(get("/coordinator/lessons/coord-l-eng-a"), token(lina))).andExpect(status().isForbidden());
        // Her own section, another subject: refused on the subject alone, and absent from her list and her calendar.
        mvc.perform(as(get("/coordinator/lessons/coord-l-eng-in-british"), token(lina))).andExpect(status().isForbidden());
        mvc.perform(as(get("/coordinator/lessons?classId=" + americanA), token(lina))).andExpect(status().isForbidden());
        // A lesson of no school at all is a 404: the refusal never doubles as confirmation that an id is real.
        mvc.perform(as(get("/coordinator/lessons/no-such-lesson"), token(lina))).andExpect(status().isNotFound());
        mvc.perform(as(get("/coordinator/lessons?classId=no-such-class"), token(lina))).andExpect(status().isNotFound());
    }

    @Test void a_scope_with_no_track_covers_both_of_them() throws Exception {
        String id = staffId("COORDINATOR", LINA);
        var both = scopeRow(id, "math", null);
        try {
            // math across both tracks still leaves Rami's english section out — the subject is the other half.
            assertThat(classNames(lina)).containsExactly("1A British", "1B British");
            var section = quest.server.ClassFixtures.section(classes, assignments, "coord-am-math", SCHOOL,
                    "american", 1, "math", staffId("TEACHER", MAYA));
            section.setName("1B American"); classes.save(section);
            assertThat(classNames(lina)).containsExactly("1B American", "1A British", "1B British");
        } finally {
            staffScopes.deleteById(both);
            classes.findById("coord-am-math").ifPresent(k -> {
                assignments.deleteAll(assignments.findByClassIdOrderBySubjectAsc("coord-am-math"));
                classes.delete(k);
            });
        }
    }

    /**
     * `requireChild` has no route of its own until R3 puts the child page behind it, so the rule is asserted against
     * the scope object directly rather than left for a later package to discover.
     */
    @Test void a_child_is_hers_only_while_she_sits_in_a_section_in_scope() {
        var caller = new quest.server.auth.Principals.User(lina, LINA, "COORDINATOR", SCHOOL);
        String mine = child("coord-kid-british", britishA), theirs = child("coord-kid-american", americanA);
        String nowhere = child("coord-kid-loose", null);
        tenant.set("COORDINATOR", SCHOOL, null);
        try {
            assertThat(scope.requireChild(caller, mine).getId()).isEqualTo(mine);
            assertThat(scope.sectionsOf(caller)).extracting(k -> k.getName()).containsExactly("1A British", "1B British");
            for (String outside : List.of(theirs, nowhere))
                assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> scope.requireChild(caller, outside), quest.server.config.ApiException.class))
                        .satisfies(e -> assertThat(e.error().code()).isEqualTo("forbidden"));
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> scope.requireChild(caller, "no-such-child"), quest.server.config.ApiException.class))
                    .satisfies(e -> assertThat(e.error().code()).isEqualTo("not_found"));
        } finally { tenant.clear(); }
    }

    @Test void the_calendar_carries_the_published_lesson_of_every_class_in_scope() throws Exception {
        var body = json(mvc.perform(as(get("/coordinator/calendar?from=" + LESSON_DAY + "&to=" + LESSON_DAY.plusDays(1)),
                token(lina))).andExpect(status().isOk()).andReturn());
        assertThat(body.get("from").asText()).isEqualTo(LESSON_DAY.toString());
        assertThat(body.get("days")).hasSize(2);

        var first = body.get("days").get(0);
        assertThat(first.get("lessons")).hasSize(1);
        assertThat(first.get("lessons").get(0).get("lessonId").asText()).isEqualTo("coord-l-math-a");
        assertThat(first.get("lessons").get(0).get("status").asText()).isEqualTo("published");
        assertThat(first.get("lessons").get(0).get("className").asText()).isEqualTo("1A British");
        assertThat(body.get("days").get(1).get("lessons").get(0).get("lessonId").asText()).isEqualTo("coord-l-math-b");

        mvc.perform(as(get("/coordinator/calendar?from=2026-03-04&to=2026-03-03"), token(lina))).andExpect(status().isBadRequest());
        mvc.perform(as(get("/coordinator/calendar?from=2026-03-04&to=2026-09-04"), token(lina))).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- read-only, and only her own area

    @Test void every_write_the_teacher_has_is_refused_for_the_role() throws Exception {
        var token = token(lina);
        var refused = List.<MockHttpServletRequestBuilder>of(
                post("/teacher/lessons").contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch("/teacher/lessons/coord-l-math-a").contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-03-05\"}"),
                post("/teacher/lessons/coord-l-math-a/publish").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/teacher/lessons/coord-l-math-a"),
                put("/teacher/marks").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/admin/coordinators").contentType(MediaType.APPLICATION_JSON).content("{}"),
                put("/admin/coordinators/" + lina + "/scopes").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/admin/teachers").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/teacher/week"), get("/teacher/classes"), get("/admin/lessons"), get("/admin/coordinators"));
        for (var request : refused) mvc.perform(as(request, token)).andExpect(status().isForbidden());
    }

    @Test void a_teacher_and_a_manager_cannot_reach_the_coordinator_area() throws Exception {
        for (String role : List.of("TEACHER", "MANAGERIAL"))
            mvc.perform(as(get("/coordinator/me"), jwt.issue("who-" + role, role + "@x.test", role, SCHOOL).token()))
                    .andExpect(status().isForbidden());
        mvc.perform(get("/coordinator/me")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- the Admin's side

    @Test void the_admin_creates_a_coordinator_and_changes_her_scope() throws Exception {
        String admin = adminToken();
        var created = json(mvc.perform(scoped(post("/admin/coordinators").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sara Coordinator\",\"email\":\"Sara.Coord@test.com\","
                                + "\"scopes\":[{\"subject\":\"science\"},{\"subject\":\"math\",\"curriculum\":\"american\"}]}"), admin))
                .andExpect(status().isCreated()).andReturn());
        String userId = created.get("coordinator").get("userId").asText();
        assertThat(created.get("temporaryPassword").asText()).isNotBlank();
        assertThat(created.get("coordinator").get("email").asText()).isEqualTo("sara.coord@test.com");
        assertThat(scopes(created.get("coordinator"))).containsExactlyInAnyOrder("science/null", "math/american");

        assertThat(json(mvc.perform(scoped(get("/admin/coordinators"), admin)).andExpect(status().isOk()).andReturn()))
                .anySatisfy(row -> assertThat(row.get("userId").asText()).isEqualTo(userId));

        var changed = json(mvc.perform(scoped(put("/admin/coordinators/" + userId + "/scopes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scopes\":[{\"subject\":\"arabic\",\"curriculum\":\"british\"}]}"), admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(scopes(changed)).containsExactly("arabic/british");

        // Refused, and nothing written: a duplicate pair, a subject the contract has no name for, and an empty set.
        for (String body : List.of("{\"scopes\":[{\"subject\":\"math\"},{\"subject\":\"math\"}]}",
                "{\"scopes\":[{\"subject\":\"astronomy\"}]}"))
            mvc.perform(scoped(put("/admin/coordinators/" + userId + "/scopes").contentType(MediaType.APPLICATION_JSON)
                    .content(body), admin)).andExpect(status().isBadRequest());
        assertThat(scopes(json(mvc.perform(scoped(put("/admin/coordinators/" + userId + "/scopes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scopes\":[{\"subject\":\"arabic\",\"curriculum\":\"british\"}]}"), admin))
                .andExpect(status().isOk()).andReturn()))).containsExactly("arabic/british");

        mvc.perform(scoped(put("/admin/coordinators/" + staffId("TEACHER", MAYA) + "/scopes")
                .contentType(MediaType.APPLICATION_JSON).content("{\"scopes\":[{\"subject\":\"math\"}]}"), admin))
                .andExpect(status().isNotFound());                                // a teacher is not a coordinator

        staffScopes.deleteAll(staffScopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(SCHOOL, userId));
        users.deleteById(userId);
    }

    // ---------------------------------------------------------------- fixture helpers

    private String token(String userId) { return jwt.issue(userId, userId + "@seed.test", "COORDINATOR", SCHOOL).token(); }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token) {
        return as(builder, token).header(quest.server.tenancy.TenantContext.HEADER, SCHOOL);
    }

    private List<String> scopes(JsonNode node) {
        var out = new ArrayList<String>();
        node.get("scopes").forEach(s -> out.add(s.get("subject").asText() + "/"
                + (s.get("curriculum") == null || s.get("curriculum").isNull() ? "null" : s.get("curriculum").asText())));
        return out;
    }

    private List<String> classNames(String coordinatorId) throws Exception {
        var out = new ArrayList<String>();
        json(mvc.perform(as(get("/coordinator/classes"), token(coordinatorId))).andExpect(status().isOk()).andReturn())
                .forEach(row -> out.add(row.get("className").asText()));
        return out;
    }

    private List<String> teacherEmails(String coordinatorId) throws Exception {
        var out = new ArrayList<String>();
        json(mvc.perform(as(get("/coordinator/teachers"), token(coordinatorId))).andExpect(status().isOk()).andReturn())
                .forEach(row -> out.add(row.get("email").asText()));
        return out;
    }

    private List<String> lessonIds(String coordinatorId, String query) throws Exception {
        var out = new ArrayList<String>();
        json(mvc.perform(as(get("/coordinator/lessons" + query), token(coordinatorId))).andExpect(status().isOk()).andReturn())
                .forEach(row -> out.add(row.get("id").asText()));
        return out;
    }

    /** A second `staff_scopes` row, returned by id so the test can take it out again. */
    private String scopeRow(String userId, String subject, String curriculum) {
        var row = new quest.server.tenancy.Entities.StaffScopeEntity();
        row.setId("coord-scope-" + subject + "-" + curriculum); row.setSchoolId(SCHOOL); row.setUserId(userId);
        row.setSubject(subject); row.setCurriculum(curriculum); row.setCreatedAt(Instant.now());
        return staffScopes.save(row).getId();
    }

    /** A child on one section's roster, or on none at all when `classId` is null. */
    private String child(String id, String classId) {
        var c = childRows.findById(id).orElseGet(quest.server.children.Entities.ChildEntity::new);
        c.setId(id); c.setSchoolId(SCHOOL); c.setName(id); c.setAvatarColor("sun");
        c.setCurriculum("british"); c.setGrade(1); c.setClassId(classId);
        if (c.getCreatedAt() == null) c.setCreatedAt(Instant.now());
        return childRows.save(c).getId();
    }

    private LessonEntity lesson(String id, String classId, String curriculum, String subject, LocalDate date, String status) {
        var l = lessons.findById(id).orElseGet(LessonEntity::new);
        l.setId(id); l.setSchoolId(SCHOOL); l.setClassId(classId); l.setCourseId(curriculum + "/1");
        l.setSubject(subject); l.setDate(date); l.setStatus(status); l.setVersion(1); l.setTitle("Lesson " + id);
        l.setSource("pdf"); l.setPublishedAt("published".equals(status) ? Instant.now() : null);
        if (l.getCreatedAt() == null) l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        return lessons.save(l);
    }

    private String idOf(String email) { return users.findBySchoolId(SCHOOL).stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow().getId(); }

    private String staffId(String role, String email) {
        return users.findBySchoolIdAndRole(SCHOOL, role).stream()
                .filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow().getId();
    }

    private String sectionId(String name) {
        return classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())
                && name.toLowerCase(Locale.ROOT).equals(k.getName() == null ? null : k.getName().toLowerCase(Locale.ROOT)))
                .findFirst().orElseThrow().getId();
    }
}
