package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.api.staff.AttendanceRequestStatus;
import quest.api.staff.AttendanceRequestType;
import quest.api.staff.CheckInRefusal;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.chat.ChatMessageRepository;
import quest.server.chat.ChatThreadRepository;
import quest.server.chat.ComplaintEventRepository;
import quest.server.children.ChildRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.staffattendance.StaffAttendanceDeviceRepository;
import quest.server.staffattendance.StaffAttendanceRequestRepository;
import quest.server.staffattendance.StaffCheckInAttemptRepository;
import quest.server.staffattendance.StaffCheckInRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TenantContext;
import quest.server.workers.WorkerRepository;

/**
 * Feature 21 end to end: `GET /me/today` — what each card holds for each role, what a manager's academy hides from
 * her, when a complaint counts as overdue, the inline decisions she may and may not take, the switch, and school
 * isolation.
 *
 * <p>Each test builds a school of its own ({@link #school}) in Asia/Riyadh on the default hours (Sunday–Thursday,
 * 07:00–14:00, grace 10) with the clock pinned to Sunday 2026-10-18, 09:00 Riyadh:
 * <ul>
 *   <li>{@code director}: a MANAGERIAL user whose `staff_scopes` row names `british` — the British academy's
 *       director and the British department's manager;</li>
 *   <li>{@code british}: a British teacher, checked in at 07:20 (late 20), with a pending remote-day request;</li>
 *   <li>{@code american}: an American teacher, with a pending request the British director may not decide, and two
 *       check-in attempts today refused because her phone is not the bound one;</li>
 *   <li>children on a British and an American section, with four complaints of different ages — one 30 hours old,
 *       one 23 hours old, one re-opened two hours ago and one re-opened 36 hours ago — and the Messages threads
 *       that carry the unread counts.</li>
 * </ul>
 */
class TodayApiTest extends ApiTestSupport {
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);
    private static final Instant NOW = SUNDAY.atTime(9, 0).atZone(RIYADH).toInstant();

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired StaffScopeRepository scopes;
    @Autowired WorkerRepository workers;
    @Autowired ClassRepository classes;
    @Autowired ChildRepository children;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired StaffCheckInRepository checkIns;
    @Autowired StaffCheckInAttemptRepository attempts;
    @Autowired StaffAttendanceDeviceRepository devices;
    @Autowired StaffAttendanceRequestRepository requests;
    @Autowired ChatThreadRepository threadRows;
    @Autowired ChatMessageRepository messageRows;
    @Autowired ComplaintEventRepository complaintEvents;
    @Autowired AdminJwtService jwt;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @BeforeEach void pin() { clock.pinTo(NOW); }
    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- the cards per role

    @Test void the_admin_sees_the_whole_school_on_every_card() throws Exception {
        var s = school("adm");
        var today = ok(scoped(get("/me/today"), s.admin(), s.id));

        assertThat(today.get("day").asText()).isEqualTo(SUNDAY.toString());
        assertThat(today.get("timeZone").asText()).as("the school's zone, not the server's").isEqualTo("Asia/Riyadh");

        var attendance = today.get("attendance");
        assertThat(attendance.get("scope").asText()).isEqualTo("SCHOOL");
        assertThat(attendance.get("counts").get("late").asInt()).isEqualTo(1);
        assertThat(attendance.get("counts").get("notArrived").asInt()).as("the others have not arrived at 09:00").isEqualTo(3);

        assertThat(ids(today.get("waiting").get("requests"))).containsExactlyInAnyOrder(s.britishRequest, s.americanRequest);
        var blocked = today.get("waiting").get("devices");
        assertThat(ids(blocked)).as("one row per person, and not the phone that was reset since").containsExactly(s.american);
        assertThat(blocked.get(0).get("kind").asText()).isEqualTo("USER");
        assertThat(blocked.get(0).get("fullName").asText()).isEqualTo("Amy American");
        assertThat(blocked.get(0).get("lastAttemptAt").asLong()).as("her latest attempt").isEqualTo(at("08:05").toEpochMilli());

        var overdue = today.get("overdueComplaints");
        assertThat(overdue.get("total").asInt()).isEqualTo(3);
        assertThat(ids(overdue.get("items")))
                .containsExactly(s.reopenedOverdueComplaint, s.overdueComplaint, s.americanComplaint);

        var unread = today.get("unread");
        assertThat(unread.get("count").asInt()).as("three from the director's thread and one from a parent").isEqualTo(4);
        assertThat(names(unread.get("senders"))).containsExactly("parent@today.test", "Dalia Director");

        assertThat(today.get("counts")).as("the old counts move to the strip").isNotEmpty();
    }

    @Test void the_manager_sees_only_her_academy_and_never_a_device() throws Exception {
        var s = school("mgr");
        var today = ok(bearer(get("/me/today"), s.token(s.director, "MANAGERIAL")));

        assertThat(today.get("attendance").get("scope").asText()).isEqualTo("ACADEMY");
        assertThat(today.get("attendance").get("counts").get("late").asInt()).isEqualTo(1);
        assertThat(ids(today.get("waiting").get("requests")))
                .as("a request she may not decide is not hers to see").containsExactly(s.britishRequest);
        assertThat(today.get("waiting").get("devices")).as("only the Admin resets a phone").isEmpty();
        assertThat(ids(today.get("overdueComplaints").get("items")))
                .as("the American academy's complaint is outside her reach")
                .containsExactly(s.reopenedOverdueComplaint, s.overdueComplaint);
        assertThat(today.get("overdueComplaints").get("total").asInt()).isEqualTo(2);
        assertThat(today.get("unread").get("count").asInt()).isEqualTo(2);
        assertThat(names(today.get("unread").get("senders"))).containsExactly("parent@today.test");
        assertThat(today.get("counts")).isNotEmpty();
    }

    @Test void a_school_with_nothing_waiting_answers_empty_cards_rather_than_absent_ones() throws Exception {
        var s = bareSchool("calm");
        var today = ok(scoped(get("/me/today"), s.admin(), s.id));

        assertThat(today.get("attendance").get("counts").get("late").asInt()).isZero();
        assertThat(today.get("waiting").get("requests")).isEmpty();
        assertThat(today.get("waiting").get("devices")).isEmpty();
        assertThat(today.get("overdueComplaints").get("total").asInt()).isZero();
        assertThat(today.get("overdueComplaints").get("items")).isEmpty();
        assertThat(today.get("unread").get("count").asInt()).isZero();
        assertThat(today.get("unread").get("senders")).isEmpty();
    }

    @Test void with_staff_attendance_off_there_is_no_attendance_card_at_all() throws Exception {
        var s = school("noatt");
        s.flag(FlagKeys.STAFF_ATTENDANCE, false);
        var today = ok(scoped(get("/me/today"), s.admin(), s.id));
        assertThat(today.get("attendance").isNull()).as("null, not an empty board").isTrue();
        assertThat(today.get("waiting").get("requests")).isEmpty();
        assertThat(today.get("waiting").get("devices")).isEmpty();
        assertThat(today.get("overdueComplaints").get("total").asInt()).as("complaints are the other feature").isEqualTo(3);
    }

    // ---------------------------------------------------------------- when a complaint is overdue

    @Test void a_complaint_is_overdue_after_a_day_and_a_re_open_starts_the_clock_again() throws Exception {
        var s = school("age");
        var items = ok(scoped(get("/me/today"), s.admin(), s.id)).get("overdueComplaints").get("items");

        assertThat(ids(items)).as("23 hours is not a day, and a complaint re-opened two hours ago is two hours old")
                .doesNotContain(s.freshComplaint, s.reopenedFreshComplaint);

        var item = item(items, s.overdueComplaint);
        assertThat(item.get("ageHours").asInt()).isEqualTo(30);
        assertThat(item.get("openedAt").asLong()).isEqualTo(NOW.minus(java.time.Duration.ofHours(30)).toEpochMilli());
        assertThat(item.get("childName").asText()).isEqualTo("Nour");
        assertThat(item.get("title").asText()).isEqualTo("The bus is late");
        assertThat(item.get("recipientName").asText()).isEqualTo("تالا المعلمة");

        var reopened = item(items, s.reopenedOverdueComplaint);
        assertThat(reopened.get("ageHours").asInt()).as("counted from the re-open, not from the first message").isEqualTo(36);
        assertThat(reopened.get("openedAt").asLong()).isEqualTo(NOW.minus(java.time.Duration.ofHours(36)).toEpochMilli());
    }

    // ---------------------------------------------------------------- the inline decisions

    @Test void the_manager_cannot_approve_a_request_outside_her_academy_nor_reset_a_phone() throws Exception {
        var s = school("deny");
        String director = s.token(s.director, "MANAGERIAL");
        mvc.perform(bearer(post("/attendance/requests/" + s.americanRequest + "/approve"), director))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(delete("/admin/staff/USER/" + s.american + "/attendance-device"), director))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(post("/attendance/requests/" + s.britishRequest + "/approve"), director))
                .andExpect(status().isOk());
    }

    @Test void a_teacher_a_coordinator_a_worker_and_a_parent_never_reach_today() throws Exception {
        var s = school("roles");
        mvc.perform(bearer(get("/me/today"), s.token(s.british, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/me/today"), s.token(s.director, "COORDINATOR"))).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/me/today"), s.token(s.worker, "WORKER"))).andExpect(status().isForbidden());
        mvc.perform(get("/me/today").header("Authorization", PARENT)).andExpect(status().isForbidden());
    }

    @Test void a_school_with_the_switch_off_has_no_today() throws Exception {
        var s = school("off");
        s.flag(FlagKeys.TODAY_HOME, false);
        mvc.perform(scoped(get("/me/today"), s.admin(), s.id)).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/me/today"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- school isolation

    @Test void another_schools_requests_complaints_and_blocked_phones_never_appear() throws Exception {
        var mine = school("mine");
        var theirs = school("theirs");
        var today = ok(scoped(get("/me/today"), mine.admin(), mine.id));

        assertThat(ids(today.get("waiting").get("requests")))
                .containsExactlyInAnyOrder(mine.britishRequest, mine.americanRequest);
        assertThat(ids(today.get("waiting").get("devices"))).containsExactly(mine.american);
        assertThat(ids(today.get("overdueComplaints").get("items")))
                .doesNotContain(theirs.overdueComplaint, theirs.americanComplaint, theirs.reopenedOverdueComplaint);
        assertThat(today.get("overdueComplaints").get("total").asInt()).isEqualTo(3);
        assertThat(today.get("unread").get("count").asInt()).isEqualTo(4);

        var mirror = ok(bearer(get("/me/today"), theirs.token(theirs.director, "MANAGERIAL")));
        assertThat(ids(mirror.get("overdueComplaints").get("items")))
                .containsExactly(theirs.reopenedOverdueComplaint, theirs.overdueComplaint);
    }

    // ---------------------------------------------------------------- the local speed guard

    /**
     * The spec's "loads in under one second on QA" cannot be measured here, so this is the guard that can be: the
     * statements `/me/today` costs must not grow with what the school holds. The dimensions that grow are children,
     * sections, complaints and Messages threads — a card that read a query per complaint or per thread would be
     * instant on this fixture and unusable on the pilot school's data. The number of employees and of pending
     * requests is left alone on purpose: feature 17's board and feature 18's `mayDecide` are per-person by design
     * (`AttendanceBoardService.academies`, `DirectManagerResolver.resolve`), and bounding them is their own change.
     */
    @Test void today_costs_the_same_whatever_the_school_holds() throws Exception {
        var s = school("count");
        String admin = s.admin(), director = s.token(s.director, "MANAGERIAL");

        // One call each first: the flag cache is cold right after the fixture invalidated it, and two reads of
        // `feature_flags` are not what this test is about.
        ok(scoped(get("/me/today"), admin, s.id));
        ok(bearer(get("/me/today"), director));

        long adminOne = statements(() -> mvc.perform(scoped(get("/me/today"), admin, s.id)).andExpect(status().isOk()));
        long managerOne = statements(() -> mvc.perform(bearer(get("/me/today"), director)).andExpect(status().isOk()));

        s.grow();

        assertThat(statements(() -> mvc.perform(scoped(get("/me/today"), admin, s.id)).andExpect(status().isOk())))
                .as("the Admin's Today must not run a query per complaint, thread or child").isEqualTo(adminOne);
        assertThat(statements(() -> mvc.perform(bearer(get("/me/today"), director)).andExpect(status().isOk())))
                .as("nor the manager's").isEqualTo(managerOne);
    }

    private interface Call { void run() throws Exception; }

    /** Statements the call costs, measured on the Hibernate session factory (as `HomeQueryCountTest` does). */
    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
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

    private static List<String> ids(JsonNode rows) {
        var out = new ArrayList<String>();
        rows.forEach(r -> out.add(r.get("id").asText()));
        return out;
    }

    private static List<String> names(JsonNode rows) {
        var out = new ArrayList<String>();
        rows.forEach(r -> out.add(r.get("name").asText()));
        return out;
    }

    private static JsonNode item(JsonNode rows, String id) {
        for (var r : rows) if (r.get("id").asText().equals(id)) return r;
        throw new AssertionError("no row " + id);
    }

    private static Instant at(String time) { return SUNDAY.atTime(LocalTime.parse(time)).atZone(RIYADH).toInstant(); }

    private static Instant hoursAgo(long hours) { return NOW.minus(java.time.Duration.ofHours(hours)); }

    /** A school with the three switches on and nobody in it yet. */
    private Fixture bareSchool(String label) {
        String id = "td21-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id);
        row.setCode(id.replace("-", "").toUpperCase(Locale.ROOT).substring(0, Math.min(20, id.replace("-", "").length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var f = new Fixture(id);
        f.flag(FlagKeys.STAFF_ATTENDANCE, true); f.flag(FlagKeys.CHAT, true); f.flag(FlagKeys.TODAY_HOME, true);
        return f;
    }

    /** …and everyone and everything the class comment describes. */
    private Fixture school(String label) {
        var f = bareSchool(label);
        f.populate();
        return f;
    }

    private final class Fixture {
        final String id;
        String adminId, director, british, american, worker;
        String britishClass, americanClass;
        String britishRequest, americanRequest;
        String overdueComplaint, freshComplaint, reopenedOverdueComplaint, reopenedFreshComplaint, americanComplaint;

        Fixture(String id) {
            this.id = id;
            adminId = user(id + "-admin", null, "ADMIN", "Amal Admin");
        }

        void populate() {
            director = user(id + "-director", id, "MANAGERIAL", "Dalia Director");
            var scope = new StaffScopeEntity();
            scope.setId(UUID.randomUUID().toString()); scope.setSchoolId(id); scope.setUserId(director);
            scope.setSubject(null); scope.setCurriculum("british"); scope.setCreatedAt(Instant.now());
            scopes.save(scope);
            british = teacher(id + "-british", "تالا المعلمة", "british");
            american = teacher(id + "-american", "Amy American", "american");
            worker = user(id + "-worker-account", id, "WORKER", "Walid Worker");
            var w = new quest.server.workers.Entities.WorkerEntity();
            w.setId(id + "-worker"); w.setSchoolId(id); w.setFullName("Walid Worker"); w.setJob("caretaker");
            w.setActive(true); w.setUserId(worker); w.setCreatedAt(Instant.now());
            workers.save(w);

            // Attendance: one late British teacher, so the board's counts are not all zero.
            checkIn(british, "07:20");

            // Feature 18: one request the British director decides, one she does not, and one already decided.
            britishRequest = request(british, AttendanceRequestStatus.PENDING, SUNDAY.plusDays(1));
            americanRequest = request(american, AttendanceRequestStatus.PENDING, SUNDAY.plusDays(1));
            request(british, AttendanceRequestStatus.APPROVED, SUNDAY.minusDays(1));

            // Feature 15: her phone is refused twice today (one row, her latest attempt); his was refused and reset.
            refusedForDevice(american, at("07:55"));
            refusedForDevice(american, at("08:05"));
            refusedForDevice(british, at("07:00"));
            resetDevice(british, at("07:30"));

            britishClass = section(id + ":british:1:math", "british", 1, british);
            americanClass = section(id + ":american:1:math", "american", 1, american);
            String kid = child(id + "-nour", "Nour", britishClass, "british");
            String calm = child(id + "-lina", "Lina", britishClass, "british");
            String otherKid = child(id + "-adam", "Adam", americanClass, "american");

            overdueComplaint = complaint(kid, british, "The bus is late", hoursAgo(30));
            freshComplaint = complaint(kid, british, "The homework app", hoursAgo(23));
            reopenedOverdueComplaint = complaint(kid, british, "Still not fixed", hoursAgo(60));
            reopen(reopenedOverdueComplaint, hoursAgo(36));
            reopenedFreshComplaint = complaint(kid, british, "Asked again", hoursAgo(60));
            reopen(reopenedFreshComplaint, hoursAgo(2));
            americanComplaint = complaint(otherKid, american, "The trip fee", hoursAgo(26));

            // Messages: the director's and the Admin's own unread, and one thread with nothing waiting on it.
            staffThread(director, adminId, 3, NOW.minusSeconds(600));
            parentThread(kid, adminId, "ADMIN", 1, NOW.minusSeconds(60));
            parentThread(kid, director, "MANAGERIAL", 2, NOW.minusSeconds(300));
            parentThread(calm, director, "MANAGERIAL", 0, NOW.minusSeconds(900));
        }

        /** Four more sections, eight more children, six more complaints and four more unread threads each. */
        void grow() {
            for (int i = 2; i <= 5; i++) {
                String classId = section(id + ":british:" + i + ":math", "british", i, british);
                String kid = child(id + "-kid-" + i, "Kid " + i, classId, "british");
                String mate = child(id + "-mate-" + i, "Mate " + i, classId, "british");
                complaint(kid, british, "Grown " + i, hoursAgo(25L + i));
                if (i % 2 == 0) complaint(mate, british, "Grown too " + i, hoursAgo(2));
                parentThread(kid, director, "MANAGERIAL", i, NOW.minusSeconds(1200L + i));
                parentThread(mate, adminId, "ADMIN", i, NOW.minusSeconds(1800L + i));
            }
        }

        String admin() { return jwt.issue(adminId, adminId + "@today.test", "ADMIN", null).token(); }

        String token(String userId, String role) { return jwt.issue(userId, userId + "@today.test", role, id).token(); }

        String teacher(String userId, String name, String curriculum) {
            user(userId, id, "TEACHER", name);
            var t = new TeacherEntity();
            t.setUserId(userId); t.setSubjectsJson("[\"math\"]"); t.setCurriculum(curriculum);
            t.setGradesJson("[1]"); t.setUpdatedAt(Instant.now());
            teachers.save(t);
            return userId;
        }

        String user(String userId, String schoolId, String role, String name) {
            var u = new UserEntity();
            u.setId(userId); u.setSchoolId(schoolId); u.setEmail(userId + "@today.test"); u.setPasswordHash("x");
            u.setRole(role); u.setStatus("active"); u.setDisplayName(name);
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            users.save(u);
            return userId;
        }

        String section(String classId, String curriculum, int grade, String teacherId) {
            var k = new ClassEntity();
            k.setId(classId); k.setSchoolId(id); k.setCurriculum(curriculum); k.setGrade(grade); k.setSubject("math");
            k.setTeacherId(teacherId); k.setName(curriculum.substring(0, 1).toUpperCase(Locale.ROOT) + grade);
            k.setActive(true); k.setCreatedAt(Instant.now());
            classes.save(k);
            return classId;
        }

        String child(String childId, String name, String classId, String curriculum) {
            var c = new quest.server.children.Entities.ChildEntity();
            c.setId(childId); c.setSchoolId(id); c.setName(name); c.setAvatarColor("sun");
            c.setCurriculum(curriculum); c.setGrade(1); c.setClassId(classId);
            c.setParentEmail("parent@today.test"); c.setActive(true); c.setCreatedAt(Instant.now());
            children.save(c);
            return childId;
        }

        void checkIn(String staffId, String time) {
            var row = new quest.server.staffattendance.Entities.StaffCheckInEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id);
            row.setStaffKind("USER"); row.setStaffId(staffId); row.setDay(SUNDAY);
            row.setCheckInAt(at(time)); row.setCheckInDistanceM(12); row.setMode("onsite");
            checkIns.save(row);
        }

        String request(String staffId, String status, LocalDate day) {
            var row = new quest.server.staffattendance.Entities.StaffAttendanceRequestEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id);
            row.setStaffKind("USER"); row.setStaffId(staffId);
            row.setType(AttendanceRequestType.REMOTE_DAY); row.setDay(day);
            row.setReason("Working from home"); row.setStatus(status);
            row.setCreatedBy(staffId); row.setCreatedAt(NOW.minusSeconds(3600));
            requests.save(row);
            return row.getId();
        }

        void refusedForDevice(String staffId, Instant when) {
            var row = new quest.server.staffattendance.Entities.StaffCheckInAttemptEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id);
            row.setStaffKind("USER"); row.setStaffId(staffId); row.setServerAt(when);
            row.setAction("CHECK_IN"); row.setResult("REFUSED"); row.setReason(CheckInRefusal.DEVICE_NOT_APPROVED);
            row.setMocked(false); row.setInstallId("another-phone");
            attempts.save(row);
        }

        void resetDevice(String staffId, Instant when) {
            var row = new quest.server.staffattendance.Entities.StaffAttendanceDeviceEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id);
            row.setStaffKind("USER"); row.setStaffId(staffId); row.setInstallId("old-phone");
            row.setFirstSeenAt(when.minusSeconds(86400)); row.setResetBy(adminId); row.setResetAt(when);
            devices.save(row);
        }

        String complaint(String childId, String staffId, String title, Instant createdAt) {
            var t = new quest.server.chat.Entities.ChatThreadEntity();
            t.setId(UUID.randomUUID().toString()); t.setSchoolId(id); t.setChildId(childId);
            t.setTeacherId(staffId); t.setStaffRole("TEACHER"); t.setTopic("complaint"); t.setStatus("open");
            t.setThreadKey(t.getId()); t.setTitle(title); t.setCreatedAt(createdAt); t.setLastMessageAt(createdAt);
            threadRows.save(t);
            return t.getId();
        }

        void reopen(String complaintId, Instant when) {
            var e = new quest.server.chat.Entities.ComplaintEventEntity();
            e.setId(UUID.randomUUID().toString()); e.setSchoolId(id); e.setThreadId(complaintId);
            e.setStatus("open"); e.setActorRole("parent"); e.setActorId("parent-" + id); e.setChangedAt(when);
            complaintEvents.save(e);
        }

        void staffThread(String staffId, String peerId, int peerUnread, Instant lastMessageAt) {
            var t = new quest.server.chat.Entities.ChatThreadEntity();
            t.setId(UUID.randomUUID().toString()); t.setSchoolId(id);
            t.setTeacherId(staffId); t.setPeerUserId(peerId); t.setStaffRole("MANAGERIAL");
            t.setTopic("question"); t.setStatus("open"); t.setThreadKey("");
            t.setCreatedAt(lastMessageAt.minusSeconds(3600)); t.setLastMessageAt(lastMessageAt);
            t.setParentUnread(peerUnread);
            threadRows.save(t);
            message(t.getId(), "peer", staffId, lastMessageAt);
        }

        void parentThread(String childId, String staffId, String staffRole, int unread, Instant lastMessageAt) {
            var t = new quest.server.chat.Entities.ChatThreadEntity();
            t.setId(UUID.randomUUID().toString()); t.setSchoolId(id); t.setChildId(childId);
            t.setTeacherId(staffId); t.setStaffRole(staffRole);
            t.setTopic("question"); t.setStatus("open"); t.setThreadKey("");
            t.setCreatedAt(lastMessageAt.minusSeconds(3600)); t.setLastMessageAt(lastMessageAt);
            t.setTeacherUnread(unread);
            threadRows.save(t);
            message(t.getId(), "parent", "parent-" + id, lastMessageAt);
        }

        void message(String threadId, String senderRole, String senderId, Instant createdAt) {
            var m = new quest.server.chat.Entities.ChatMessageEntity();
            m.setId(UUID.randomUUID().toString()); m.setSchoolId(id); m.setThreadId(threadId);
            m.setSenderRole(senderRole); m.setSenderId(senderId); m.setBody("Please look at this");
            m.setCreatedAt(createdAt);
            messageRows.save(m);
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
