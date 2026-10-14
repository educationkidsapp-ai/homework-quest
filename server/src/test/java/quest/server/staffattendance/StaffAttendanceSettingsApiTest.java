package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TenantContext;
import quest.server.workers.WorkerRepository;

/**
 * Feature 14: the Attendance settings page and every employee's direct manager, end to end.
 *
 * <p>Three schools of the test's own: {@link #SCHOOL} and {@link #OTHER} with `staffAttendance` on, which is where
 * isolation is proven (another school's settings, staff and managers are never read or written), and {@link #OFF}
 * with it off, where the routes do not exist.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffAttendanceSettingsApiTest extends ApiTestSupport {
    private static final String SCHOOL = "sa14-school", OTHER = "sa14-other", OFF = "sa14-off";
    private static final String DIRECTOR = "sa14-director", LATE_DIRECTOR = "sa14-director-2", TEACHER = "sa14-teacher",
            NO_ACADEMY_TEACHER = "sa14-teacher-plain", COORDINATOR = "sa14-coord", WORKER = "sa14-worker",
            OTHER_MANAGER = "sa14-other-manager", OTHER_TEACHER = "sa14-other-teacher", OFF_MANAGER = "sa14-off-manager";

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired StaffScopeRepository scopes;
    @Autowired WorkerRepository workers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AdminJwtService jwt;
    @Autowired DirectManagerResolver resolver;
    @Autowired ScheduleResolver schedules;

    @BeforeAll void fixture() {
        school(SCHOOL); school(OTHER); school(OFF);
        flag(SCHOOL, true); flag(OTHER, true); flag(OFF, false);
        // Two British directors: the earlier one is the fallback, every time.
        user(DIRECTOR, SCHOOL, "MANAGERIAL", "Dalia Director", Instant.parse("2026-01-01T00:00:00Z"));
        user(LATE_DIRECTOR, SCHOOL, "MANAGERIAL", "Later Director", Instant.parse("2026-06-01T00:00:00Z"));
        scope("sa14-scope-1", DIRECTOR, SCHOOL, null, "british");
        scope("sa14-scope-2", LATE_DIRECTOR, SCHOOL, null, "british");
        user(TEACHER, SCHOOL, "TEACHER", "Tala Teacher", Instant.now());
        teacher(TEACHER, "british");
        user(NO_ACADEMY_TEACHER, SCHOOL, "TEACHER", "Plain Teacher", Instant.now());
        // An American coordinator, and no American director: she falls through to the Admin.
        user(COORDINATOR, SCHOOL, "COORDINATOR", "Coco Coordinator", Instant.now());
        scope("sa14-scope-3", COORDINATOR, SCHOOL, "math", "american");
        worker(WORKER, SCHOOL, "Walid Worker");
        user(OTHER_MANAGER, OTHER, "MANAGERIAL", "Other Manager", Instant.now());
        user(OTHER_TEACHER, OTHER, "TEACHER", "Other Teacher", Instant.now());
        user(OFF_MANAGER, OFF, "MANAGERIAL", "Off Manager", Instant.now());
    }

    // ---------------------------------------------------------------- the settings page

    @Test void a_school_that_saved_nothing_reads_the_defaults() throws Exception {
        var page = ok(scoped(get("/admin/attendance-settings"), adminToken(), OTHER));
        assertThat(page.get("radiusMeters").asInt()).isEqualTo(150);
        assertThat(page.get("graceMinutes").asInt()).isEqualTo(10);
        assertThat(page.get("notArrivedMinutes").asInt()).isEqualTo(60);
        assertThat(page.get("missingCheckOutMinutes").asInt()).isEqualTo(60);
        assertThat(page.get("directorNotify").asText()).isEqualTo("DIGEST");
        assertThat(page.get("digestTime").asText()).isEqualTo("07:30");
        assertThat(page.get("timeZone").asText()).isNotBlank();
        assertThat(page.get("schedules")).hasSize(3);
        assertThat(page.get("schedules").get(0).get("weekdays").toString())
                .isEqualTo("[\"SUNDAY\",\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\"]");
    }

    @Test void the_admin_saves_the_page_and_a_manager_reads_it_but_cannot_change_it() throws Exception {
        String admin = adminToken();
        var saved = ok(scoped(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON).content(VALID), admin, SCHOOL));
        assertThat(saved.get("latitude").asDouble()).isEqualTo(24.7136);
        assertThat(saved.get("radiusMeters").asInt()).isEqualTo(200);
        assertThat(saved.get("graceMinutes").asInt()).isEqualTo(15);
        assertThat(saved.get("adminNotify").asText()).isEqualTo("OFF");
        assertThat(saved.get("holidays")).hasSize(1);
        var teacherHours = saved.get("schedules").get(0);
        assertThat(teacherHours.get("category").asText()).isEqualTo("TEACHER");
        assertThat(teacherHours.get("ramadanStart").asText()).isEqualTo("09:00");

        var managerView = ok(bearer(get("/admin/attendance-settings"), token(DIRECTOR, "MANAGERIAL", SCHOOL)));
        assertThat(managerView.get("radiusMeters").asInt()).isEqualTo(200);
        mvc.perform(bearer(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON).content(VALID),
                token(DIRECTOR, "MANAGERIAL", SCHOOL))).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/admin/attendance-settings"), token(TEACHER, "TEACHER", SCHOOL))).andExpect(status().isForbidden());

        // The saved hours are what the resolver reads: a Ramadan Wednesday and a holiday Tuesday.
        var ramadan = schedules.expected(SCHOOL, "TEACHER", LocalDate.of(2027, 2, 17));
        assertThat(ramadan.ramadan()).isTrue();
        assertThat(ramadan.start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(schedules.expected(SCHOOL, "TEACHER", LocalDate.of(2026, 11, 17)).kind()).isEqualTo(ScheduleResolver.Kind.HOLIDAY);
        assertThat(schedules.expected(SCHOOL, "WORKER", LocalDate.of(2026, 10, 13)).start()).as("unsent category keeps the default").isEqualTo(LocalTime.of(7, 0));
    }

    @Test void a_parent_cannot_reach_the_staff_routes() throws Exception {
        int status = mvc.perform(get("/admin/attendance-settings").header("Authorization", PARENT)).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }

    @Test void the_page_is_validated() throws Exception {
        String admin = adminToken();
        for (String bad : new String[] {
                "{\"radiusMeters\":49}",
                "{\"radiusMeters\":501}",
                "{\"latitude\":24.7}",
                "{\"graceMinutes\":-1}",
                "{\"directorNotify\":\"SOMETIMES\"}",
                "{\"schedules\":[{\"category\":\"TEACHER\",\"weekdays\":[\"SUNDAY\"],\"start\":\"14:00\",\"end\":\"07:00\"}]}",
                "{\"schedules\":[{\"category\":\"TEACHER\",\"weekdays\":[\"SUNDAY\"],\"start\":\"07:00\",\"end\":\"07:00\"}]}",
                "{\"schedules\":[{\"category\":\"TEACHER\",\"weekdays\":[],\"start\":\"07:00\",\"end\":\"14:00\"}]}",
                "{\"schedules\":[{\"category\":\"JANITOR\",\"weekdays\":[\"SUNDAY\"],\"start\":\"07:00\",\"end\":\"14:00\"}]}",
                "{\"schedules\":[{\"category\":\"TEACHER\",\"weekdays\":[\"SUNDAY\"],\"start\":\"07:00\",\"end\":\"14:00\",\"ramadanFrom\":\"2027-02-08\"}]}",
                "{\"schedules\":[{\"category\":\"TEACHER\",\"weekdays\":[\"SUNDAY\"],\"start\":\"07:00\",\"end\":\"14:00\",\"ramadanFrom\":\"2027-03-09\",\"ramadanTo\":\"2027-02-08\",\"ramadanStart\":\"09:00\",\"ramadanEnd\":\"13:00\"}]}",
                "{\"schedules\":[{\"category\":\"TEACHER\",\"weekdays\":[\"SUNDAY\"],\"start\":\"07:00\",\"end\":\"14:00\",\"ramadanFrom\":\"2027-02-08\",\"ramadanTo\":\"2027-03-09\",\"ramadanStart\":\"13:00\",\"ramadanEnd\":\"09:00\"}]}",
                "{\"holidays\":[{\"name\":\"Break\",\"from\":\"2026-11-19\",\"to\":\"2026-11-15\"}]}",
                "{\"holidays\":[{\"name\":\"\",\"from\":\"2026-11-15\",\"to\":\"2026-11-19\"}]}"}) {
            var result = mvc.perform(scoped(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON).content(bad), admin, OTHER)).andReturn();
            assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        for (String good : new String[] {"{\"radiusMeters\":50}", "{\"radiusMeters\":500}"})
            mvc.perform(scoped(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON).content(good), admin, SCHOOL))
                    .andExpect(status().isOk());
    }

    @Test void with_the_switch_off_the_routes_do_not_exist() throws Exception {
        mvc.perform(bearer(get("/admin/attendance-settings"), token(OFF_MANAGER, "MANAGERIAL", OFF))).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/admin/staff/attendance-profiles"), token(OFF_MANAGER, "MANAGERIAL", OFF))).andExpect(status().isNotFound());
    }

    @Test void one_schools_settings_and_staff_are_invisible_from_another() throws Exception {
        String admin = adminToken();
        ok(scoped(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON).content(VALID), admin, SCHOOL));
        var otherView = ok(bearer(get("/admin/attendance-settings"), token(OTHER_MANAGER, "MANAGERIAL", OTHER)));
        assertThat(otherView.get("latitude").isNull()).isTrue();
        assertThat(otherView.toString()).doesNotContain("Mid-term");

        var otherStaff = ok(bearer(get("/admin/staff/attendance-profiles"), token(OTHER_MANAGER, "MANAGERIAL", OTHER))).toString();
        assertThat(otherStaff).contains(OTHER_TEACHER).doesNotContain(TEACHER + "\"").doesNotContain(WORKER);

        // Another school's employee is not found, and another school's manager is not a manager here.
        mvc.perform(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON)
                .content(profiles(OTHER_MANAGER, "USER", TEACHER)), admin, OTHER)).andExpect(status().isNotFound());
        mvc.perform(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON)
                .content(profiles(DIRECTOR, "USER", OTHER_TEACHER)), admin, OTHER)).andExpect(status().isBadRequest());
        // A token of one school pointed at another is refused before anything is read.
        mvc.perform(bearer(get("/admin/attendance-settings"), token(OTHER_MANAGER, "MANAGERIAL", OTHER)).header(TenantContext.HEADER, SCHOOL))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- direct managers

    @Test void the_admin_sets_a_direct_manager_for_one_employee_and_for_several_and_a_manager_cannot() throws Exception {
        String admin = adminToken();
        var list = ok(scoped(get("/admin/staff/attendance-profiles"), admin, SCHOOL));
        var worker = find(list, WORKER);
        assertThat(worker.get("kind").asText()).isEqualTo("WORKER");
        assertThat(worker.get("category").asText()).isEqualTo("WORKER");
        assertThat(worker.get("categorySet").asBoolean()).isFalse();
        assertThat(find(list, COORDINATOR).get("category").asText()).isEqualTo("ADMINISTRATIVE");
        assertThat(find(list, TEACHER).get("category").asText()).isEqualTo("TEACHER");

        // Several at once: a worker and a coordinator under the later director.
        var bulk = ok(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON).content(
                "{\"staff\":[{\"kind\":\"WORKER\",\"id\":\"" + WORKER + "\"},{\"kind\":\"USER\",\"id\":\"" + COORDINATOR + "\"}],"
                        + "\"directManagerId\":\"" + LATE_DIRECTOR + "\"}"), admin, SCHOOL));
        assertThat(bulk).hasSize(2);
        assertThat(bulk.get(0).get("directManagerName").asText()).isEqualTo("Later Director");

        // One, from the edit form: a manager and a category together.
        var one = ok(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON).content(
                "{\"staff\":[{\"kind\":\"USER\",\"id\":\"" + NO_ACADEMY_TEACHER + "\"}],\"directManagerId\":\"" + DIRECTOR
                        + "\",\"category\":\"ADMINISTRATIVE\"}"), admin, SCHOOL));
        assertThat(one.get(0).get("category").asText()).isEqualTo("ADMINISTRATIVE");
        assertThat(one.get(0).get("categorySet").asBoolean()).isTrue();

        var after = ok(scoped(get("/admin/staff/attendance-profiles"), admin, SCHOOL));
        assertThat(find(after, WORKER).get("directManagerId").asText()).isEqualTo(LATE_DIRECTOR);
        assertThat(find(after, COORDINATOR).get("directManagerId").asText()).isEqualTo(LATE_DIRECTOR);
        assertThat(find(after, NO_ACADEMY_TEACHER).get("directManagerId").asText()).isEqualTo(DIRECTOR);

        // Not her own manager; not a write a manager may make.
        mvc.perform(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON)
                .content(profiles(DIRECTOR, "USER", DIRECTOR)), admin, SCHOOL)).andExpect(status().isBadRequest());
        mvc.perform(bearer(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON)
                .content(profiles(DIRECTOR, "USER", TEACHER)), token(DIRECTOR, "MANAGERIAL", SCHOOL))).andExpect(status().isForbidden());

        // Clearing puts the fallback back.
        ok(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON).content(
                "{\"staff\":[{\"kind\":\"WORKER\",\"id\":\"" + WORKER + "\"},{\"kind\":\"USER\",\"id\":\"" + COORDINATOR + "\"},"
                        + "{\"kind\":\"USER\",\"id\":\"" + NO_ACADEMY_TEACHER + "\"}],\"clearDirectManager\":true}"), admin, SCHOOL));
        assertThat(find(ok(scoped(get("/admin/staff/attendance-profiles"), admin, SCHOOL)), WORKER).get("directManagerId").isNull()).isTrue();
    }

    @Test void without_a_direct_manager_the_academy_director_then_the_admin_is_resolved() throws Exception {
        String admin = adminToken();
        // A teacher of the British academy: the earliest British director.
        assertThat(resolver.resolve(SCHOOL, "USER", TEACHER)).isEqualTo(new DirectManagerResolver.Resolved(DirectManagerResolver.Source.DIRECTOR, DIRECTOR));
        // An American coordinator with no American director, a teacher with no academy, a worker: the Admin.
        assertThat(resolver.resolve(SCHOOL, "USER", COORDINATOR).source()).isEqualTo(DirectManagerResolver.Source.ADMIN);
        assertThat(resolver.resolve(SCHOOL, "USER", NO_ACADEMY_TEACHER).source()).isEqualTo(DirectManagerResolver.Source.ADMIN);
        assertThat(resolver.resolve(SCHOOL, "WORKER", WORKER).source()).isEqualTo(DirectManagerResolver.Source.ADMIN);
        // A director is not her own fallback.
        assertThat(resolver.resolve(SCHOOL, "USER", DIRECTOR).source()).isEqualTo(DirectManagerResolver.Source.ADMIN);

        // Once set, the direct manager wins over the director.
        ok(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON)
                .content(profiles(LATE_DIRECTOR, "USER", TEACHER)), admin, SCHOOL));
        assertThat(resolver.resolve(SCHOOL, "USER", TEACHER)).isEqualTo(new DirectManagerResolver.Resolved(DirectManagerResolver.Source.DIRECT, LATE_DIRECTOR));
        ok(scoped(put("/admin/staff/attendance-profiles").contentType(MediaType.APPLICATION_JSON).content(
                "{\"staff\":[{\"kind\":\"USER\",\"id\":\"" + TEACHER + "\"}],\"clearDirectManager\":true}"), admin, SCHOOL));
        assertThat(resolver.resolve(SCHOOL, "USER", TEACHER).source()).isEqualTo(DirectManagerResolver.Source.DIRECTOR);
    }

    // ---------------------------------------------------------------- fixture

    private static final String VALID = """
            {"latitude":24.7136,"longitude":46.6753,"radiusMeters":200,"graceMinutes":15,"notArrivedMinutes":45,
             "missingCheckOutMinutes":30,"directorNotify":"IMMEDIATE","adminNotify":"OFF","digestTime":"08:00",
             "schedules":[{"category":"TEACHER","weekdays":["SUNDAY","MONDAY","TUESDAY","WEDNESDAY","THURSDAY"],
                           "start":"07:00","end":"14:00","ramadanFrom":"2027-02-08","ramadanTo":"2027-03-09",
                           "ramadanStart":"09:00","ramadanEnd":"13:00"},
                          {"category":"ADMINISTRATIVE","weekdays":["SUNDAY","MONDAY","TUESDAY","WEDNESDAY","THURSDAY"],
                           "start":"07:30","end":"15:30"}],
             "holidays":[{"name":"Mid-term break","from":"2026-11-15","to":"2026-11-19"}]}
            """;

    private static String profiles(String managerId, String kind, String staffId) {
        return "{\"staff\":[{\"kind\":\"" + kind + "\",\"id\":\"" + staffId + "\"}],\"directManagerId\":\"" + managerId + "\"}";
    }

    private static JsonNode find(JsonNode list, String id) {
        for (var row : list) if (id.equals(row.get("id").asText())) return row;
        throw new AssertionError(id + " not in " + list);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return json(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return builder.header("Authorization", "Bearer " + token).header(TenantContext.HEADER, schoolId);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private String token(String userId, String role, String schoolId) { return jwt.issue(userId, userId + "@sa14.test", role, schoolId).token(); }

    private void school(String id) {
        schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(id); s.setCode(id.replace("-", "").toUpperCase());
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    private void flag(String schoolId, boolean enabled) {
        var row = schoolFlags.findOne(schoolId, FlagKeys.STAFF_ATTENDANCE).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(FlagKeys.STAFF_ATTENDANCE);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
        featureFlags.invalidate(schoolId);
    }

    private void user(String id, String schoolId, String role, String name, Instant createdAt) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@sa14.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(name);
        u.setCreatedAt(createdAt); u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    private void teacher(String userId, String curriculum) {
        var t = teachers.findById(userId).orElseGet(TeacherEntity::new);
        t.setUserId(userId); t.setCurriculum(curriculum); t.setUpdatedAt(Instant.now());
        teachers.save(t);
    }

    private void scope(String id, String userId, String schoolId, String subject, String curriculum) {
        var row = scopes.findById(id).orElseGet(StaffScopeEntity::new);
        row.setId(id); row.setSchoolId(schoolId); row.setUserId(userId); row.setSubject(subject); row.setCurriculum(curriculum);
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        scopes.save(row);
    }

    private void worker(String id, String schoolId, String name) {
        var w = workers.findById(id).orElseGet(quest.server.workers.Entities.WorkerEntity::new);
        w.setId(id); w.setSchoolId(schoolId); w.setFullName(name); w.setJob("caretaker"); w.setActive(true);
        if (w.getCreatedAt() == null) w.setCreatedAt(Instant.now());
        workers.save(w);
    }
}
