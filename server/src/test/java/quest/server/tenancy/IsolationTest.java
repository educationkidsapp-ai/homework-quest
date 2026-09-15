package quest.server.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.api.CreateLessonRequest;
import quest.api.LessonFilter;
import quest.api.LessonSource;
import quest.api.dto.Curriculum;
import quest.api.dto.Subject;
import quest.server.ApiTestSupport;
import quest.server.admin.AdminLessonService;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Principals;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.children.ChildService;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;

/**
 * P1.2: a token of school A never reads a row of school B — over every tenant endpoint in `server/openapi.json`, and
 * over the services the endpoints sit on. Users and lessons are written straight through the repositories because
 * invites and the dashboard sign-in are P1.3's package.
 *
 * <p>The HTTP half runs as ADMIN with `X-School-Id`, because `SecurityConfig` still gates `/admin/**` on
 * `hasRole("ADMIN")` until P1.3 replaces it with the `permissions.json` matrix; a TEACHER token is asserted to be
 * refused there, and her isolation is proven against the services, which is where this package enforces it.
 *
 * <p>The scope also fails closed: `users.school_id` is nullable and the token omits the claim when it is null, so a
 * TEACHER or MANAGERIAL principal can arrive with no school — which used to mean "no filter", the ADMIN path. Such a
 * principal is now refused by {@link TenantContext} itself, before the interceptor, before any transaction and
 * therefore before any repository call, and the tests below pin all three.
 */
class IsolationTest extends ApiTestSupport {
    private static final String A = "school-a", B = "school-b";
    private static final String LESSON_A = "lesson-of-a", LESSON_B = "lesson-of-b";
    private static final String TEACHER_NO_SCHOOL = "teacher-schoolless", MANAGER_NO_SCHOOL = "manager-schoolless";
    private static final String PANEL = "{\"objectives\":{\"en\":[\"Count\"],\"ar\":[\"Count\"]},\"supported\":[],\"challenge\":[],\"stopTips\":[],\"modelAnswers\":[]}";
    private static final LocalDate DATE_A = LocalDate.of(2027, 4, 7), DATE_B = LocalDate.of(2027, 4, 9);

    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired LessonRepository lessons;
    @Autowired AdminJwtService jwt;
    @Autowired TenantContext tenant;
    @Autowired AdminLessonService lessonService;
    @Autowired ChildService childService;

    @BeforeEach void seedTwoSchools() {
        school(A, "Alpha Academy", "SCHLAA"); school(B, "Beta School", "SCHLBB");
        user("teacher-a", A, "teacher@alpha.test", "TEACHER"); teacher("teacher-a", "[\"math\"]", "british", "[1,2]");
        user("manager-a", A, "manager@alpha.test", "MANAGERIAL");
        user("teacher-b", B, "teacher@beta.test", "TEACHER"); teacher("teacher-b", "[\"math\"]", "british", "[1,2]");
        user("manager-b", B, "manager@beta.test", "MANAGERIAL");
        user(TEACHER_NO_SCHOOL, null, "teacher@nowhere.test", "TEACHER"); teacher(TEACHER_NO_SCHOOL, "[\"math\"]", "british", "[1,2]");
        user(MANAGER_NO_SCHOOL, null, "manager@nowhere.test", "MANAGERIAL");
        klass(A, "math", "teacher-a"); klass(B, "math", "teacher-b");
        lesson(LESSON_A, A, DATE_A); lesson(LESSON_B, B, DATE_B);
    }

    // ---------------------------------------------------------------- the school switcher

    @Test void an_admin_without_a_header_reads_across_schools() throws Exception {
        var ids = lessonIds(adminToken(), null);
        assertThat(ids).contains(LESSON_A, LESSON_B);
    }

    @Test void an_admin_scoped_to_a_school_reads_only_that_school() throws Exception {
        var token = adminToken();
        assertThat(lessonIds(token, A)).contains(LESSON_A).doesNotContain(LESSON_B);
        assertThat(lessonIds(token, B)).contains(LESSON_B).doesNotContain(LESSON_A);
    }

    @Test void an_unknown_school_header_is_not_found() throws Exception {
        var body = json(mvc.perform(scoped(get("/admin/lessons"), adminToken(), "no-such-school")).andExpect(status().isNotFound()).andReturn());
        assertThat(body.get("code").asText()).isEqualTo("not_found");
    }

    @Test void a_teacher_may_not_point_the_header_at_another_school() throws Exception {
        var teacherToken = jwt.issue("teacher-a", "teacher@alpha.test", "TEACHER", A).token();
        mvc.perform(scoped(get("/admin/lessons"), teacherToken, B)).andExpect(status().isForbidden());
        try {                                                                   // the interceptor itself, not the role gate
            tenant.set("TEACHER", A, B);
            assertThatThrownBy(tenant::resolveEagerly).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("forbidden"));
            tenant.set("TEACHER", A, A);                                        // her own school in the header is fine
            tenant.resolveEagerly();
            assertThat(tenant.schoolId()).isEqualTo(A);
        } finally { tenant.clear(); }
    }

    // ---------------------------------------------------------------- every route of /admin/lessons/{id}

    static Stream<Arguments> routesOfAnotherSchool() {
        return Stream.of(
                Arguments.of("GET", "/admin/lessons/" + LESSON_B),
                Arguments.of("POST", "/admin/lessons/" + LESSON_B + "/analyze"),
                Arguments.of("POST", "/admin/lessons/" + LESSON_B + "/skills"),
                Arguments.of("TEXT", "/admin/lessons/" + LESSON_B + "/generate-from-text"),
                Arguments.of("POST", "/admin/lessons/" + LESSON_B + "/publish"),
                Arguments.of("POST", "/admin/lessons/" + LESSON_B + "/unpublish"),
                Arguments.of("POST", "/admin/lessons/" + LESSON_B + "/retry"),
                Arguments.of("POST", "/admin/lessons/" + LESSON_B + "/steps/analyze/retry"),
                Arguments.of("PANEL", "/admin/lessons/" + LESSON_B + "/parent-panel"),
                Arguments.of("DELETE", "/admin/lessons/" + LESSON_B + "/files"),
                Arguments.of("DELETE", "/admin/lessons/" + LESSON_B),
                Arguments.of("MULTIPART", "/admin/lessons/" + LESSON_B + "/files"),
                Arguments.of("MULTIPART", "/admin/lessons/" + LESSON_B + "/images"));
    }

    @ParameterizedTest(name = "{0} {1} of another school is 404")
    @MethodSource("routesOfAnotherSchool")
    void a_lesson_of_another_school_is_not_found(String method, String path) throws Exception {
        var result = mvc.perform(scoped(requestFor(method, path), adminToken(), A)).andReturn();
        assertThat(result.getResponse().getStatus()).as("%s %s", method, path).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(LESSON_B);
    }

    // ---------------------------------------------------------------- a principal with no school at all

    /** The routes above plus the collection and report routes: nothing a school-less dashboard token may reach. */
    static Stream<Arguments> tenantRoutes() {
        return Stream.concat(
                Stream.of(Arguments.of("GET", "/admin/lessons"),
                        Arguments.of("GET", "/admin/lessons/" + LESSON_A),
                        Arguments.of("GET", "/admin/usage"),
                        Arguments.of("GET", "/admin/calendar?curriculum=british&grade=1&year=2027&month=4")),
                routesOfAnotherSchool());
    }

    @ParameterizedTest(name = "{0} {1} is 403 for a token with no school")
    @MethodSource("tenantRoutes")
    void a_school_less_dashboard_token_is_refused_on_every_tenant_route(String method, String path) throws Exception {
        for (var principal : List.of(List.of("TEACHER", TEACHER_NO_SCHOOL), List.of("MANAGERIAL", MANAGER_NO_SCHOOL))) {
            var token = jwt.issue(principal.get(1), principal.get(1) + "@nowhere.test", principal.get(0), null).token();
            var result = mvc.perform(requestFor(method, path).header("Authorization", "Bearer " + token)).andReturn();
            assertThat(result.getResponse().getStatus()).as("%s %s as %s", method, path, principal.get(0)).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(LESSON_A).doesNotContain(LESSON_B);
        }
    }

    @Test void a_dashboard_token_with_no_school_is_refused_before_any_handler() {
        try {
            for (String role : List.of("TEACHER", "MANAGERIAL")) {
                tenant.set(role, null, null);                                   // the JWT omits `schoolId` when `users.school_id` is null
                forbidden(tenant::resolveEagerly);                              // no `X-School-Id` needed for the refusal
                forbidden(tenant::schoolId);
                forbidden(tenant::writeSchoolId);
                tenant.set(role, "no-such-school", null);                       // a school id no `schools` row has
                forbidden(tenant::resolveEagerly);
                forbidden(tenant::schoolId);
            }
            tenant.set("ADMIN", null, null);                                    // D6 is untouched: the platform ADMIN still reads across schools
            tenant.resolveEagerly();
            assertThat(tenant.schoolId()).isNull();
            assertThat(tenant.unfilteredAllowed()).isTrue();
        } finally { tenant.clear(); }
        assertThat(tenant.schoolId()).isNull();                                 // a parent or a job carries no scope at all
    }

    @Test void no_repository_query_runs_unfiltered_for_a_principal_without_a_school() {
        for (var principal : List.of(List.of("TEACHER", TEACHER_NO_SCHOOL), List.of("MANAGERIAL", MANAGER_NO_SCHOOL)))
            as(principal.get(0), principal.get(1), null, () -> {
                forbidden(() -> lessons.findAll());                             // the transaction is refused before it begins
                forbidden(() -> lessons.findAllByOrderByDateDescCreatedAtDesc());
                forbidden(() -> lessons.findOneById(LESSON_A));
                forbidden(() -> lessonService.list(filter()));
                forbidden(() -> lessonService.get(LESSON_A));
                forbidden(() -> childService.scoped("any-child-id"));
            });
    }

    @Test void a_parent_may_not_upload_attempts_for_a_lesson_of_another_school() throws Exception {
        var childA = parentPost("/children", "{\"name\":\"Amal\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"SCHLAA\"}").get("id").asText();
        var upload = "[{\"id\":\"cross-school-1\",\"stopId\":\"" + LESSON_B + ":stop-1\",\"lessonId\":\"" + LESSON_B + "\",\"level\":1,\"answerJson\":\"{}\","
                + "\"correct\":true,\"attemptNumber\":1,\"mistakes\":0,\"stars\":3,\"answeredAt\":1789300000000}]";
        var body = json(mvc.perform(post("/children/" + childA + "/attempts").header("Authorization", PARENT)
                .contentType(MediaType.APPLICATION_JSON).content(upload)).andExpect(status().isForbidden()).andReturn());
        assertThat(body.get("code").asText()).isEqualTo("forbidden");
    }

    // ---------------------------------------------------------------- the report endpoints

    @Test void usage_and_calendar_only_count_the_selected_school() throws Exception {
        var token = adminToken();
        assertThat(mvc.perform(scoped(get("/admin/usage"), token, A)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).doesNotContain(LESSON_B);
        var calendar = json(mvc.perform(scoped(get("/admin/calendar?curriculum=british&grade=1&year=2027&month=4"), token, A)).andExpect(status().isOk()).andReturn());
        assertThat(calendar.get("days")).anySatisfy(d -> assertThat(d.get("date").asText()).isEqualTo(DATE_A.toString()));
        assertThat(calendar.get("days")).allSatisfy(d -> assertThat(d.get("date").asText()).isNotEqualTo(DATE_B.toString()));
    }

    @Test void the_cache_report_is_global_but_names_no_lesson_of_another_school() throws Exception {
        var token = adminToken();
        var a = lessons.findById(LESSON_A).orElseThrow(); a.setSourceHash("shared-hash"); lessons.save(a);
        var b = lessons.findById(LESSON_B).orElseThrow(); b.setSourceHash("shared-hash"); lessons.save(b);
        var body = mvc.perform(scoped(get("/admin/cache"), token, A)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(LESSON_B);                              // the analysis cache stays global, its lesson list does not
    }

    // ---------------------------------------------------------------- the services, where the roles are enforced

    @Test void a_teacher_of_one_school_reads_nothing_of_the_other() {
        as("TEACHER", "teacher-a", A, () -> {
            assertThat(lessonService.list(filter()).stream().map(l -> l.getId())).contains(LESSON_A).doesNotContain(LESSON_B);
            assertThatThrownBy(() -> lessonService.get(LESSON_B)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("not_found"));
            assertThat(lessonService.get(LESSON_A).getId()).isEqualTo(LESSON_A);
        });
    }

    @Test void a_managerial_user_reads_her_school_and_writes_nothing() {
        as("MANAGERIAL", "manager-a", A, () -> {
            assertThat(lessonService.list(filter()).stream().map(l -> l.getId())).contains(LESSON_A).doesNotContain(LESSON_B);
            for (Runnable write : List.<Runnable>of(
                    () -> lessonService.publish(LESSON_A), () -> lessonService.unpublish(LESSON_A), () -> lessonService.delete(LESSON_A),
                    () -> lessonService.retry(LESSON_A), () -> lessonService.deleteFiles(LESSON_A), () -> lessonService.analyze(LESSON_A),
                    () -> lessonService.create(new CreateLessonRequest(Curriculum.BRITISH, 1, Subject.MATH, kdate(DATE_A), null, 7, LessonSource.MANUAL, "Managerial"), null)))
                assertThatThrownBy(write::run).isInstanceOf(ApiException.class)
                        .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("forbidden"));
        });
    }

    @Test void a_teacher_creates_lessons_only_for_her_subject_curriculum_and_grades() {
        as("TEACHER", "teacher-a", A, () -> {
            assertThat(created(Curriculum.BRITISH, 1, Subject.MATH)).isNotNull();                    // hers
            assertThatThrownBy(() -> created(Curriculum.BRITISH, 1, Subject.ENGLISH)).hasMessageContaining("subject:");
            assertThatThrownBy(() -> created(Curriculum.AMERICAN, 1, Subject.MATH)).hasMessageContaining("curriculum:");
            assertThatThrownBy(() -> created(Curriculum.BRITISH, 3, Subject.MATH)).hasMessageContaining("grade:");
        });
        var mine = lessons.findAll().stream().filter(l -> A.equals(l.getSchoolId()) && !LESSON_A.equals(l.getId())).toList();
        assertThat(mine).isNotEmpty().allSatisfy(l -> assertThat(classes.findById(l.getClassId()).orElseThrow().getTeacherId()).isEqualTo("teacher-a"));
    }

    @Test void a_child_of_another_school_is_not_found_for_a_scoped_caller() throws Exception {
        var childA = parentPost("/children", "{\"name\":\"Amal\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"SCHLAA\"}").get("id").asText();
        var childB = parentPost("/children", "{\"name\":\"Basem\",\"avatarColor\":\"mint\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"SCHLBB\"}").get("id").asText();
        as("TEACHER", "teacher-a", A, () -> {
            assertThat(childService.scoped(childA).getId()).isEqualTo(childA);
            assertThatThrownBy(() -> childService.scoped(childB)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("not_found"));
        });
    }

    @Test void a_parent_never_reaches_a_lesson_of_another_school() throws Exception {
        var childA = parentPost("/children", "{\"name\":\"Amal\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"SCHLAA\"}").get("id").asText();
        var seeded = lessons.findAll().stream().filter(l -> "published".equals(l.getStatus()) && "default".equals(l.getSchoolId()) && "british/1".equals(l.getCourseId())).findFirst().orElseThrow();
        mvc.perform(get("/lessons/" + seeded.getId() + "?childId=" + childA).header("Authorization", PARENT)).andExpect(status().isNotFound());
        var map = parentGet("/children/" + childA + "/map?from=2027-04-01&to=2027-04-30&today=2027-04-07");
        assertThat(map.toString()).doesNotContain(LESSON_B).doesNotContain(seeded.getId());
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode adminList(String token, String schoolId) throws Exception {
        return json(mvc.perform(scoped(get("/admin/lessons"), token, schoolId)).andExpect(status().isOk()).andReturn());
    }

    private List<String> lessonIds(String token, String schoolId) throws Exception {
        var out = new java.util.ArrayList<String>();
        adminList(token, schoolId).forEach(l -> out.add(l.get("id").asText()));
        return out;
    }

    /** The request each row of the parameterised route lists stands for. */
    private static MockHttpServletRequestBuilder requestFor(String method, String path) {
        return switch (method) {
            case "GET" -> get(path);
            case "TEXT" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"A lesson about counting to ten together.\"}");
            case "PANEL" -> put(path).contentType(MediaType.APPLICATION_JSON).content(PANEL);
            case "DELETE" -> delete(path);
            case "MULTIPART" -> multipart(path).file(new MockMultipartFile("files", "s.pdf", "application/pdf", new byte[] {1}))
                    .file(new MockMultipartFile("file", "s.png", "image/png", new byte[] {1}));
            default -> post(path).contentType(MediaType.APPLICATION_JSON).content("[]");
        };
    }

    private static void forbidden(ThrowingCallable body) {
        assertThatThrownBy(body).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("forbidden"));
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder b, String token, String schoolId) {
        var r = b.header("Authorization", "Bearer " + token);
        return schoolId == null ? r : r.header(TenantContext.HEADER, schoolId);
    }

    /** Runs the body as a dashboard user of one school, exactly as the JWT filter would set it up. */
    private void as(String role, String userId, String schoolId, Runnable body) {
        tenant.set(role, schoolId, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new Principals.User(userId, userId + "@test.local", role, schoolId), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        try { body.run(); } finally { tenant.clear(); SecurityContextHolder.clearContext(); }
    }

    private String created(Curriculum curriculum, int grade, Subject subject) {
        return lessonService.create(new CreateLessonRequest(curriculum, grade, subject, kdate(DATE_A), null, 7, LessonSource.MANUAL, "Teacher lesson"), null).getId();
    }

    private static LessonFilter filter() { return new LessonFilter(null, null, null, null, null); }
    private static kotlinx.datetime.LocalDate kdate(LocalDate d) { return new kotlinx.datetime.LocalDate(d.getYear(), d.getMonthValue(), d.getDayOfMonth()); }

    private void school(String id, String name, String code) {
        if (schools.existsById(id)) return;
        var s = new Entities.SchoolEntity();
        s.setId(id); s.setName(name); s.setCode(code); s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]"); s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private void user(String id, String schoolId, String email, String role) {
        if (users.existsById(id)) return;
        var u = new quest.server.auth.Entities.UserEntity();
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(email); u.setPasswordHash("x"); u.setRole(role); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    private void teacher(String userId, String subjectsJson, String curriculum, String gradesJson) {
        var t = new quest.server.auth.Entities.TeacherEntity();
        t.setUserId(userId); t.setSubjectsJson(subjectsJson); t.setCurriculum(curriculum); t.setGradesJson(gradesJson); t.setUpdatedAt(Instant.now());
        teachers.save(t);
    }

    private void klass(String schoolId, String subject, String teacherId) {
        String id = schoolId + ":british:1:" + subject;
        if (classes.existsById(id)) return;
        var k = new Entities.ClassEntity();
        k.setId(id); k.setSchoolId(schoolId); k.setCurriculum("british"); k.setGrade(1); k.setSubject(subject); k.setTeacherId(teacherId); k.setCreatedAt(Instant.now());
        classes.save(k);
    }

    private void lesson(String id, String schoolId, LocalDate date) {
        if (lessons.existsById(id)) return;
        var l = new LessonEntity();
        l.setId(id); l.setSchoolId(schoolId); l.setClassId(schoolId + ":british:1:math"); l.setCourseId("british/1"); l.setSubject("math");
        l.setDate(date); l.setStatus("published"); l.setVersion(1); l.setTitle("Lesson of " + schoolId); l.setSource("pdf");
        l.setCreatedAt(Instant.now()); l.setUpdatedAt(Instant.now()); l.setPublishedAt(Instant.now());
        lessons.save(l);
    }
}
