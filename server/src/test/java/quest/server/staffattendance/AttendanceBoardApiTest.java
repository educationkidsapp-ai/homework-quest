package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Feature 17 end to end: who sees whom on the board and in the report, school isolation, the switch, one employee's
 * day (attempts, alerts, and the manual marks of before), the monthly report against the daily records, the CSV, and
 * the manual register refused while the feature is on.
 *
 * <p>Each test builds its own school ({@link #school}) on the defaults (Sunday–Thursday, 07:00–14:00 in Riyadh, grace
 * 10, thresholds 60) with the clock pinned to Sunday 2026-10-18, 09:00 in Riyadh:
 * <ul>
 *   <li>{@code director}: a manager whose department row names `british` — the British academy's director;</li>
 *   <li>{@code british}: a British teacher, checked in at 07:20 (late 20); her name is Arabic;</li>
 *   <li>{@code american}: an American teacher (no American director), whose direct manager is {@code lead};</li>
 *   <li>{@code lead}: an American teacher who leads {@code american}; {@code loner}: a teacher who leads nobody;</li>
 *   <li>a worker, with no app account.</li>
 * </ul>
 */
class AttendanceBoardApiTest extends ApiTestSupport {
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired StaffScopeRepository scopes;
    @Autowired WorkerRepository workers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired StaffAttendanceProfileRepository profiles;
    @Autowired StaffCheckInRepository checkIns;
    @Autowired AdminJwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired quest.server.tenancy.ClassRepository sections;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository teachingAssignments;
    @Autowired quest.server.floors.FloorRepository floors;
    @Autowired quest.server.floors.FloorClassRepository floorClasses;
    @Autowired quest.server.floors.FloorSupervisorRepository floorSupervisors;

    @BeforeEach void pin() { clock.pinTo(SUNDAY.atTime(9, 0).atZone(RIYADH).toInstant()); }
    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- who sees whom

    @Test void the_admin_sees_every_employee_and_the_counts_match_the_table() throws Exception {
        var s = school("adm");
        var board = ok(scoped(get("/staff-attendance/board"), adminToken(), s.id));
        assertThat(board.get("scope").asText()).isEqualTo("SCHOOL");
        assertThat(board.get("day").asText()).isEqualTo(SUNDAY.toString());
        assertThat(ids(board)).containsExactlyInAnyOrder(s.director, s.british, s.american, s.lead, s.loner, s.worker);

        var british = row(board, s.british);
        assertThat(british.get("status").asText()).isEqualTo("LATE");
        assertThat(british.get("lateMinutes").asInt()).isEqualTo(20);
        assertThat(british.get("checkIn").asText()).isEqualTo("07:20");
        assertThat(british.get("mode").asText()).isEqualTo("onsite");
        assertThat(british.get("academies").toString()).isEqualTo("[\"british\"]");
        assertThat(row(board, s.worker).get("category").asText()).isEqualTo("WORKER");
        assertThat(row(board, s.american).get("status").asText()).as("09:00, no check-in").isEqualTo("NOT_ARRIVED");

        var counts = board.get("counts");
        assertThat(counts.get("late").asInt()).isEqualTo(count(board, "LATE"));
        assertThat(counts.get("notArrived").asInt()).isEqualTo(count(board, "NOT_ARRIVED"));
        assertThat(counts.get("present").asInt()).isEqualTo(count(board, "PRESENT"));
        assertThat(counts.get("remote").asInt()).isEqualTo(count(board, "REMOTE"));
        assertThat(counts.get("late").asInt() + counts.get("notArrived").asInt()).isEqualTo(6);
    }

    @Test void a_director_sees_only_her_academy() throws Exception {
        var s = school("dir");
        var board = ok(bearer(get("/staff-attendance/board"), token(s.director, "MANAGERIAL", s.id)));
        assertThat(board.get("scope").asText()).isEqualTo("ACADEMY");
        assertThat(ids(board)).containsExactly(s.british);
        var report = ok(bearer(get("/staff-attendance/report?month=2026-10"), token(s.director, "MANAGERIAL", s.id)));
        assertThat(ids(report)).containsExactly(s.british);
    }

    @Test void a_direct_manager_sees_only_her_people_and_nobody_else_s_day() throws Exception {
        var s = school("lead");
        String lead = token(s.lead, "TEACHER", s.id);
        var board = ok(bearer(get("/staff-attendance/board"), lead));
        assertThat(board.get("scope").asText()).isEqualTo("REPORTS");
        assertThat(ids(board)).containsExactly(s.american);

        ok(bearer(get("/staff-attendance/employees/USER/" + s.american), lead));
        mvc.perform(bearer(get("/staff-attendance/employees/USER/" + s.british), lead)).andExpect(status().isNotFound());
    }

    @Test void a_teacher_who_leads_nobody_sees_an_empty_board() throws Exception {
        var s = school("loner");
        var board = ok(bearer(get("/staff-attendance/board"), token(s.loner, "TEACHER", s.id)));
        assertThat(board.get("rows")).isEmpty();
        assertThat(board.get("counts").get("notArrived").asInt()).isZero();
    }

    @Test void a_worker_and_a_parent_never_reach_the_board() throws Exception {
        var s = school("deny");
        String workerAccount = s.user("wacct", "WORKER", "Worker account");
        mvc.perform(bearer(get("/staff-attendance/board"), token(workerAccount, "WORKER", s.id))).andExpect(status().isForbidden());
        mvc.perform(get("/staff-attendance/board").header("Authorization", PARENT)).andExpect(status().isForbidden());
        mvc.perform(get("/staff-attendance/report.csv").header("Authorization", PARENT)).andExpect(status().isForbidden());
    }

    @Test void a_school_with_the_switch_off_has_no_board() throws Exception {
        var s = school("off");
        s.flag(FlagKeys.STAFF_ATTENDANCE, false);
        mvc.perform(scoped(get("/staff-attendance/board"), adminToken(), s.id)).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/staff-attendance/report"), token(s.director, "MANAGERIAL", s.id))).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- school isolation

    @Test void one_school_never_sees_another_school_s_staff() throws Exception {
        var a = school("isoa");
        var b = school("isob");
        String admin = adminToken();
        var boardB = ok(scoped(get("/staff-attendance/board"), admin, b.id));
        assertThat(ids(boardB)).doesNotContain(a.director, a.british, a.american, a.lead, a.loner, a.worker);
        assertThat(ids(ok(scoped(get("/staff-attendance/report"), admin, b.id)))).doesNotContain(a.british);
        mvc.perform(scoped(get("/staff-attendance/employees/USER/" + a.british), admin, b.id)).andExpect(status().isNotFound());

        // A manager's token is her school's: another school's header is refused, and without it she sees her own.
        String directorB = token(b.director, "MANAGERIAL", b.id);
        mvc.perform(scoped(get("/staff-attendance/board"), directorB, a.id)).andExpect(status().isForbidden());
        assertThat(ids(ok(bearer(get("/staff-attendance/board"), directorB)))).containsExactly(b.british);
    }

    // ---------------------------------------------------------------- one employee's day

    @Test void the_employee_day_shows_attempts_with_reasons_alerts_and_old_manual_marks() throws Exception {
        var s = school("day");
        Instant refusedAt = SUNDAY.atTime(7, 12).atZone(RIYADH).toInstant();
        jdbc.update("INSERT INTO staff_check_in_attempts (id, school_id, staff_kind, staff_id, server_at, action, distance_m, mocked, result, reason)"
                + " VALUES (?, ?, 'USER', ?, ?, 'CHECK_IN', 420, FALSE, 'REFUSED', 'OUTSIDE')",
                UUID.randomUUID().toString(), s.id, s.british, java.sql.Timestamp.from(refusedAt));
        jdbc.update("INSERT INTO staff_attendance_alerts (id, school_id, staff_kind, staff_id, work_day, type, sent_at) VALUES (?, ?, 'USER', ?, ?, 'LATE', ?)",
                UUID.randomUUID().toString(), s.id, s.british, java.sql.Date.valueOf(SUNDAY),
                java.sql.Timestamp.from(SUNDAY.atTime(7, 25).atZone(RIYADH).toInstant()));
        // Marked by hand before the switch: Wednesday the 14th, late.
        jdbc.update("INSERT INTO staff_attendance (id, school_id, user_id, date, status, marked_by, marked_at) VALUES (?, ?, ?, ?, 'late', ?, ?)",
                UUID.randomUUID().toString(), s.id, s.british, java.sql.Date.valueOf(LocalDate.of(2026, 10, 14)), s.director,
                java.sql.Timestamp.from(Instant.parse("2026-10-14T08:00:00Z")));

        var day = ok(scoped(get("/staff-attendance/employees/USER/" + s.british), adminToken(), s.id));
        assertThat(day.get("day").asText()).isEqualTo(SUNDAY.toString());
        assertThat(day.get("fullName").asText()).isEqualTo("تالا المعلمة");
        assertThat(day.get("attempts")).singleElement().satisfies(a -> {
            assertThat(a.get("at").asText()).isEqualTo("07:12");
            assertThat(a.get("result").asText()).isEqualTo("REFUSED");
            assertThat(a.get("reason").asText()).isEqualTo("OUTSIDE");
            assertThat(a.get("distanceMeters").asInt()).isEqualTo(420);
        });
        assertThat(day.get("alerts")).singleElement().satisfies(a -> {
            assertThat(a.get("type").asText()).isEqualTo("LATE");
            assertThat(a.get("sentAt").asText()).isEqualTo("07:25");
        });
        assertThat(day.get("month").asText()).isEqualTo("2026-10");
        assertThat(day.get("calendar")).hasSize(31);
        var wednesday = day.get("calendar").get(13);
        assertThat(wednesday.get("date").asText()).isEqualTo("2026-10-14");
        assertThat(wednesday.get("mode").asText()).isEqualTo("manual");
        assertThat(wednesday.get("status").asText()).isEqualTo("LATE");
        var sunday = day.get("calendar").get(17);
        assertThat(sunday.get("mode").asText()).isEqualTo("onsite");
        assertThat(sunday.get("lateMinutes").asInt()).isEqualTo(20);
    }

    // ---------------------------------------------------------------- the monthly report

    @Test void the_report_totals_match_the_daily_records_and_export_as_csv() throws Exception {
        var s = school("rep");
        s.checkIn(s.british, LocalDate.of(2026, 10, 15), "06:58", "14:00");               // Thursday, on time
        s.checkIn(s.british, LocalDate.of(2026, 10, 14), "07:00", "13:15");               // Wednesday, early leave 45
        s.checkIn(s.british, LocalDate.of(2026, 10, 13), "07:31", null);                  // Tuesday, late 31, no check-out
        s.checkIn(s.british, LocalDate.of(2026, 9, 30), "07:00", "14:00");                // September: not in October's report
        String admin = adminToken();

        var report = ok(scoped(get("/staff-attendance/report?month=2026-10"), admin, s.id));
        assertThat(report.get("month").asText()).isEqualTo("2026-10");
        var r = row(report, s.british);
        // Working days to today: Thu 1, Sun 4 – Thu 8, Sun 11 – Thu 15, Sun 18 = 12.
        assertThat(r.get("workingDays").asInt()).isEqualTo(12);
        assertThat(r.get("presentOnsite").asInt()).as("13, 14, 15 and today").isEqualTo(4);
        assertThat(r.get("presentRemote").asInt()).isZero();
        assertThat(r.get("lateCount").asInt()).as("the 13th and today").isEqualTo(2);
        assertThat(r.get("lateMinutes").asInt()).isEqualTo(31 + 20);
        assertThat(r.get("absentDays").asInt()).as("12 working days, 4 present").isEqualTo(8);
        assertThat(r.get("earlyLeaveMinutes").asInt()).isEqualTo(45);
        assertThat(r.get("missingCheckOuts").asInt()).as("the 13th; today is not over").isEqualTo(1);
        assertThat(r.get("corrections").asInt()).isZero();

        // The sample's days, read one by one from the calendar, add up to the same numbers.
        var calendar = ok(scoped(get("/staff-attendance/employees/USER/" + s.british + "?day=2026-10-18"), admin, s.id)).get("calendar");
        int late = 0, lateMinutes = 0, absent = 0;
        for (var d : calendar) {
            if (d.get("status").asText().equals("LATE")) { late++; lateMinutes += d.get("lateMinutes").asInt(); }
            if (d.get("status").asText().equals("ABSENT")) absent++;
        }
        assertThat(late).isEqualTo(r.get("lateCount").asInt());
        assertThat(lateMinutes).isEqualTo(r.get("lateMinutes").asInt());
        assertThat(absent).isEqualTo(r.get("absentDays").asInt());

        var csv = mvc.perform(scoped(get("/staff-attendance/report.csv?month=2026-10"), admin, s.id))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getHeader("Content-Disposition")).contains("staff-attendance-2026-10.csv");
        byte[] body = csv.getContentAsByteArray();
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("تالا المعلمة,TEACHER,british,12,4,0,2,51,8,45,1,0");

        var xlsx = mvc.perform(scoped(get("/staff-attendance/report.xlsx?month=2026-10"), admin, s.id))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(xlsx.getContentType()).isEqualTo(quest.server.grading.GradingExports.XLSX);
        mvc.perform(scoped(get("/staff-attendance/report?month=October"), admin, s.id)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- the manual register (milestone 06)

    @Test void with_the_feature_on_the_manual_register_is_refused_and_its_history_stays_readable() throws Exception {
        var s = school("manual");
        String director = token(s.director, "MANAGERIAL", s.id);
        String body = "[{\"userId\":\"" + s.british + "\",\"status\":\"present\"}]";
        var refused = mvc.perform(bearer(put("/management/staff-attendance?day=" + SUNDAY).contentType(MediaType.APPLICATION_JSON).content(body), director))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).toString()).contains("manual_marking_off");
        mvc.perform(bearer(get("/management/staff-attendance/summary?month=2026-10"), director)).andExpect(status().isOk());
    }

    /**
     * Feature 20: a FLOOR_SUPERVISOR sees the teachers assigned to the classes on her floors — plus, like everybody
     * else, anyone whose direct manager she is, and never herself. `BoardScope` reads the floors through
     * `FloorScope`, so a class she does not hold is not on her board however the request is shaped.
     */
    @Test void a_floor_supervisor_sees_the_teachers_on_her_floors_and_her_own_reports() throws Exception {
        var s = school("floorsup");
        String nadia = s.user("nadia", quest.server.tenancy.FloorScope.ROLE, "Nadia Floor");
        String onFloor = s.section("1A"), elsewhere = s.section("2A");
        s.assign(s.british, onFloor); s.assign(s.american, elsewhere);
        s.floor("Second floor", nadia, onFloor);
        s.reportsTo(s.loner, nadia);

        var board = ok(bearer(get("/staff-attendance/board"), token(nadia, quest.server.tenancy.FloorScope.ROLE, s.id)));
        assertThat(board.get("scope").asText()).isEqualTo("REPORTS");
        assertThat(ids(board)).as("the teacher on her floor and her one direct report, never the American teacher or herself")
                .containsExactlyInAnyOrder(s.british, s.loner);
        ok(bearer(get("/staff-attendance/employees/USER/" + s.british), token(nadia, quest.server.tenancy.FloorScope.ROLE, s.id)));
        mvc.perform(bearer(get("/staff-attendance/employees/USER/" + s.american), token(nadia, quest.server.tenancy.FloorScope.ROLE, s.id)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return json(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private static MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return builder.header("Authorization", "Bearer " + token).header(TenantContext.HEADER, schoolId);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private String token(String userId, String role, String schoolId) { return jwt.issue(userId, userId + "@ab17.test", role, schoolId).token(); }

    private static List<String> ids(JsonNode page) {
        var out = new ArrayList<String>();
        page.get("rows").forEach(r -> out.add(r.get("id").asText()));
        return out;
    }

    private static JsonNode row(JsonNode page, String id) {
        for (var r : page.get("rows")) if (r.get("id").asText().equals(id)) return r;
        throw new AssertionError("no row " + id);
    }

    private static int count(JsonNode board, String status) {
        int n = 0;
        for (var r : board.get("rows")) if (r.get("status").asText().equals(status)) n++;
        return n;
    }

    /** A school of the test's own, with the cast described on the class. */
    private School school(String label) {
        String id = "ab17-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id); row.setCode(id.replace("-", "").toUpperCase(Locale.ROOT).substring(0, Math.min(20, id.replace("-", "").length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var s = new School(id);
        s.flag(FlagKeys.STAFF_ATTENDANCE, true);
        return s;
    }

    private final class School {
        final String id, director, british, american, lead, loner, worker;

        School(String id) {
            this.id = id;
            director = user("director", "MANAGERIAL", "Dalia Director");
            var scope = new StaffScopeEntity();
            scope.setId(UUID.randomUUID().toString()); scope.setSchoolId(id); scope.setUserId(director);
            scope.setSubject(null); scope.setCurriculum("british"); scope.setCreatedAt(Instant.now());
            scopes.save(scope);
            british = teacher("british", "تالا المعلمة", "british");
            american = teacher("american", "Amy American", "american");
            lead = teacher("lead", "Lina Lead", "american");
            loner = teacher("loner", "Lone Teacher", "american");
            var w = new quest.server.workers.Entities.WorkerEntity();
            w.setId(id + "-worker"); w.setSchoolId(id); w.setFullName("Walid Worker"); w.setJob("caretaker");
            w.setActive(true); w.setCreatedAt(Instant.now());
            workers.save(w);
            worker = w.getId();
            var p = new Entities.StaffAttendanceProfileEntity();
            p.setId(UUID.randomUUID().toString()); p.setSchoolId(id); p.setStaffKind("USER"); p.setStaffId(american);
            p.setDirectManagerId(lead); p.setUpdatedAt(Instant.now());
            profiles.save(p);
            checkIn(british, SUNDAY, "07:20", null);
        }

        String teacher(String label, String name, String curriculum) {
            String userId = user(label, "TEACHER", name);
            var t = new TeacherEntity();
            t.setUserId(userId); t.setCurriculum(curriculum); t.setUpdatedAt(Instant.now());
            teachers.save(t);
            return userId;
        }

        /** Feature 20: a section, an assignment on it, a floor holding it, and one direct report. */
        String section(String name) {
            var k = new quest.server.tenancy.Entities.ClassEntity();
            k.setId(id + "-" + name); k.setSchoolId(id); k.setCurriculum("british"); k.setGrade(1);
            k.setName(name); k.setActive(true); k.setCreatedAt(Instant.now());
            return sections.save(k).getId();
        }

        void assign(String teacherId, String classId) {
            var a = new quest.server.tenancy.Entities.TeachingAssignmentEntity();
            a.setId(UUID.randomUUID().toString()); a.setSchoolId(id); a.setTeacherId(teacherId);
            a.setClassId(classId); a.setSubject("math"); a.setCreatedAt(Instant.now());
            teachingAssignments.save(a);
        }

        void floor(String name, String supervisorId, String classId) {
            var f = new quest.server.floors.Entities.FloorEntity();
            f.setId(id + "-floor"); f.setSchoolId(id); f.setName(name); f.setAcademy("british"); f.setCreatedAt(Instant.now());
            floors.save(f);
            var on = new quest.server.floors.Entities.FloorClassEntity();
            on.setId(UUID.randomUUID().toString()); on.setFloorId(f.getId()); on.setSchoolId(id);
            on.setSectionId(classId); on.setCreatedAt(Instant.now());
            floorClasses.save(on);
            var holder = new quest.server.floors.Entities.FloorSupervisorEntity();
            holder.setId(UUID.randomUUID().toString()); holder.setFloorId(f.getId()); holder.setSchoolId(id);
            holder.setUserId(supervisorId); holder.setCreatedAt(Instant.now());
            floorSupervisors.save(holder);
        }

        void reportsTo(String employee, String manager) {
            var p = new Entities.StaffAttendanceProfileEntity();
            p.setId(UUID.randomUUID().toString()); p.setSchoolId(id); p.setStaffKind("USER"); p.setStaffId(employee);
            p.setDirectManagerId(manager); p.setUpdatedAt(Instant.now());
            profiles.save(p);
        }

        String user(String label, String role, String name) {
            String userId = id + "-" + label;
            var u = new UserEntity();
            u.setId(userId); u.setSchoolId(id); u.setEmail(userId + "@ab17.test"); u.setPasswordHash("x");
            u.setRole(role); u.setStatus("active"); u.setDisplayName(name);
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            users.save(u);
            return userId;
        }

        void checkIn(String employee, LocalDate day, String in, String out) {
            var row = new Entities.StaffCheckInEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id); row.setStaffKind("USER"); row.setStaffId(employee);
            row.setDay(day);
            row.setCheckInAt(day.atTime(LocalTime.parse(in)).atZone(RIYADH).toInstant());
            row.setCheckInDistanceM(12);
            if (out != null) {
                row.setCheckOutAt(day.atTime(LocalTime.parse(out)).atZone(RIYADH).toInstant());
                row.setCheckOutDistanceM(14);
            }
            row.setMode("onsite");
            checkIns.save(row);
        }

        void flag(String key, boolean enabled) {
            var row = schoolFlags.findOne(id, key).orElseGet(() -> {
                var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
                fresh.setSchoolId(id); fresh.setFlagKey(key);
                return fresh;
            });
            row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
            schoolFlags.save(row);
            featureFlags.invalidate(id);
        }
    }
}
