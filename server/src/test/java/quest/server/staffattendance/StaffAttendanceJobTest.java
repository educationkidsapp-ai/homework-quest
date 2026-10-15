package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import quest.api.dto.AttendanceLink;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.workers.WorkerRepository;

/**
 * Feature 16: the attendance job end to end — statuses, reminders, the direct manager's alerts, the fallback chain, the
 * morning digest's scope, the director's and the Admin's policies, once-only sending, and school isolation.
 *
 * <p>Every test builds a school of its own ({@link #school}), in Asia/Riyadh with the default hours (Sunday–Thursday,
 * 07:00–14:00, grace 10, both thresholds 60, digest 07:30), and runs {@link StaffAttendanceJob#run(String)} for it with
 * the clock pinned, so no test sees another's rows. Sunday 2026-10-18: 04:00Z is 07:00 in Riyadh.
 */
class StaffAttendanceJobTest extends ApiTestSupport {
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);

    @Autowired StaffAttendanceJob job;
    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired StaffScopeRepository scopes;
    @Autowired WorkerRepository workers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AttendanceSettingsRepository settings;
    @Autowired AttendanceHolidayRepository holidays;
    @Autowired StaffAttendanceProfileRepository profiles;
    @Autowired StaffCheckInRepository checkIns;
    @Autowired AdminJwtService jwt;
    @Autowired PushSender pushSender;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkScheduleRepository workSchedules;

    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- the day without a check-in

    @Test void with_no_check_in_she_is_reminded_at_start_the_manager_is_told_at_the_threshold_and_the_day_ends_absent() {
        var s = school("nc");
        String teacher = s.teacher("t", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);

        run(s, "06:59");
        assertThat(rows(teacher)).isEmpty();

        run(s, "07:00");
        assertThat(rows(teacher)).singleElement().satisfies(r -> {
            assertThat(r.get("KIND")).isEqualTo("attendance.reminder");
            assertThat(params(r).get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.START_REMINDER);
        });
        assertThat(alerts(manager)).isEmpty();

        run(s, "08:00");
        assertThat(alerts(manager)).singleElement().satisfies(r -> {
            assertThat(r.get("KIND")).isEqualTo("attendance.alert");
            assertThat(params(r).get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.ALERT_NOT_ARRIVED);
            assertThat(r.get("BODY").toString()).contains("has not arrived yet");
        });
        assertThat(day(s, teacher).get("NOT_ARRIVED")).isEqualTo(true);
        assertThat(day(s, teacher).get("ABSENT")).isEqualTo(false);

        run(s, "14:00");
        assertThat(day(s, teacher).get("ABSENT")).isEqualTo(true);
        assertThat(rows(teacher)).hasSize(1);
        assertThat(alerts(manager)).hasSize(1);
    }

    @Test void each_notification_arrives_once_even_when_the_job_runs_twice() {
        var s = school("twice");
        String teacher = s.teacher("t", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);
        s.checkIn(manager, "06:50", null);                                             // she is here: no noise about her
        int first = run(s, "08:00");
        int second = run(s, "08:00");
        int later = run(s, "08:05");
        assertThat(first).as("her reminder, the manager's alert and each Admin's digest (it is past 07:30)")
                .isEqualTo(2 + users.findActiveAdminIds().size());
        assertThat(second).isZero();
        assertThat(later).isZero();
        assertThat(rows(teacher)).hasSize(1);
        assertThat(alerts(manager)).hasSize(1);
    }

    // ---------------------------------------------------------------- check-ins

    @Test void a_late_check_in_tells_the_direct_manager_with_the_minutes_after_start() {
        var s = school("late");
        String teacher = s.teacher("t", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);
        s.checkIn(teacher, "07:18", null);
        run(s, "07:20");
        assertThat(alerts(manager)).singleElement().satisfies(r -> {
            var p = params(r);
            assertThat(p.get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.ALERT_LATE);
            assertThat(p.get(AttendanceLink.MINUTES)).isEqualTo("18");
            assertThat(p.get(AttendanceLink.STAFF)).isEqualTo("USER:" + teacher);
            assertThat(p.get(AttendanceLink.DATE)).isEqualTo(SUNDAY.toString());
            assertThat(r.get("BODY").toString()).isEqualTo(s.name(teacher) + " is 18 minutes late");
        });
        assertThat(rows(teacher)).as("no reminder: she checked in").isEmpty();
        assertThat(day(s, teacher).get("LATE_MINUTES")).isEqualTo(18);
    }

    /**
     * Feature 20: she may be a direct manager, which is the acceptance criterion "set as direct manager, she receives
     * lateness alerts for her teachers". Nothing in the job knows about floors — she is simply staff of the school
     * now (`AttendanceStaff.STAFF_ROLES`), which is the whole change.
     */
    @Test void a_floor_supervisor_set_as_direct_manager_hears_about_a_late_check_in() {
        var s = school("floorsup");
        String teacher = s.teacher("t", "british"), nadia = s.supervisor("n");
        s.directManager(teacher, nadia);
        s.checkIn(teacher, "07:18", null);
        run(s, "07:20");
        assertThat(alerts(nadia)).singleElement().satisfies(r -> {
            var p = params(r);
            assertThat(p.get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.ALERT_LATE);
            assertThat(p.get(AttendanceLink.STAFF)).isEqualTo("USER:" + teacher);
            assertThat(p.get(AttendanceLink.MINUTES)).isEqualTo("18");
        });
    }

    @Test void an_on_time_check_in_tells_nobody() {
        var s = school("ontime");
        String teacher = s.teacher("t", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);
        s.checkIn(teacher, "07:10", null);
        run(s, "09:00");
        assertThat(alerts(manager)).isEmpty();
        assertThat(rows(teacher)).isEmpty();
        assertThat(day(s, teacher).get("LATE_MINUTES")).isNull();
    }

    @Test void an_early_check_out_is_early_leave_and_the_manager_is_told() {
        var s = school("early");
        String teacher = s.teacher("t", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);
        s.checkIn(teacher, "06:55", "13:20");
        run(s, "13:25");
        assertThat(alerts(manager)).singleElement().satisfies(r -> {
            assertThat(params(r).get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.ALERT_EARLY_LEAVE);
            assertThat(params(r).get(AttendanceLink.MINUTES)).isEqualTo("40");
        });
        assertThat(day(s, teacher).get("EARLY_LEAVE_MINUTES")).isEqualTo(40);
    }

    @Test void a_missing_check_out_reminds_her_at_the_end_then_tells_the_manager() {
        var s = school("out");
        String teacher = s.teacher("t", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);
        s.checkIn(teacher, "06:55", null);
        run(s, "14:00");
        assertThat(rows(teacher)).singleElement().satisfies(r ->
                assertThat(params(r).get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.END_REMINDER));
        assertThat(alerts(manager)).isEmpty();
        run(s, "15:00");
        assertThat(rows(teacher)).extracting(r -> params(r).get(AttendanceLink.TYPE))
                .containsExactlyInAnyOrder(AttendanceLink.END_REMINDER, AttendanceLink.MISSING_CHECKOUT_REMINDER);
        assertThat(alerts(manager)).singleElement().satisfies(r ->
                assertThat(params(r).get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.ALERT_MISSING_CHECKOUT));
        assertThat(day(s, teacher).get("MISSING_CHECKOUT")).isEqualTo(true);
    }

    // ---------------------------------------------------------------- days with nothing

    @Test void holidays_and_weekends_produce_no_statuses_and_no_notifications() {
        var s = school("off");
        String teacher = s.teacher("t", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);
        clock.pinTo(Instant.parse("2026-10-16T12:00:00Z"));                          // Friday, 15:00 in Riyadh
        assertThat(job.run(s.id)).isZero();
        s.holiday(SUNDAY);
        assertThat(run(s, "15:00")).isZero();
        assertThat(rows(teacher)).isEmpty();
        assertThat(alerts(manager)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_attendance_days WHERE school_id = ?", Integer.class, s.id)).isZero();
    }

    @Test void ramadan_hours_are_used_inside_the_ramadan_range() {
        var s = school("ramadan");
        String teacher = s.teacher("t", "british");
        // Through Hibernate, as feature 14's page saves it: a raw SQL TIME is read back shifted by the JVM's zone.
        var hours = new Entities.WorkScheduleEntity();
        hours.setId(UUID.randomUUID().toString()); hours.setSchoolId(s.id); hours.setCategory("TEACHER");
        hours.setWeekdays("SUNDAY,MONDAY,TUESDAY,WEDNESDAY,THURSDAY");
        hours.setStartTime(LocalTime.of(7, 0)); hours.setEndTime(LocalTime.of(14, 0));
        hours.setRamadanFrom(LocalDate.of(2027, 2, 8)); hours.setRamadanTo(LocalDate.of(2027, 3, 9));
        hours.setRamadanStart(LocalTime.of(9, 0)); hours.setRamadanEnd(LocalTime.of(13, 0));
        workSchedules.save(hours);
        LocalDate ramadan = LocalDate.of(2027, 2, 14);
        clock.pinTo(ramadan.atTime(8, 30).atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant());
        job.run(s.id);
        assertThat(rows(teacher)).as("07:00 means nothing in Ramadan").isEmpty();
        clock.pinTo(ramadan.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant());
        job.run(s.id);
        assertThat(rows(teacher)).singleElement().satisfies(r ->
                assertThat(params(r).get(AttendanceLink.DATE)).isEqualTo(ramadan.toString()));
    }

    // ---------------------------------------------------------------- recipients

    @Test void without_a_direct_manager_the_academy_director_is_told_and_a_worker_falls_back_to_the_admin() {
        var s = school("fallback");
        String teacher = s.teacher("t", "british"), director = s.manager("d", "british");
        String workerAccount = s.worker("w");
        run(s, "08:00");
        assertThat(alerts(director)).singleElement().satisfies(r -> assertThat(r.get("BODY").toString()).contains(s.name(teacher)));
        for (String admin : users.findActiveAdminIds())
            assertThat(rowsIn(admin, s.id)).as("the worker's not-arrived alert reaches every Admin")
                    .anyMatch(r -> params(r).get(AttendanceLink.STAFF).startsWith("WORKER:"));
        assertThat(rows(workerAccount)).as("her own reminder, through her account").singleElement()
                .satisfies(r -> assertThat(params(r).get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.START_REMINDER));
    }

    @Test void the_digest_arrives_at_the_set_time_with_her_academy_for_the_director_and_the_school_for_the_admin() {
        var s = school("digest");
        String british = s.teacher("tb", "british"), american = s.teacher("ta", "american");
        String director = s.manager("d", "british"), manager = s.manager("m", null);
        s.directManager(british, manager); s.directManager(american, manager);
        s.checkIn(british, "07:20", null);                                             // late; the American has not arrived
        s.checkIn(director, "06:50", null); s.checkIn(manager, "06:50", null);        // the leaders are here

        run(s, "07:29");
        assertThat(digests(director, s.id)).isEmpty();

        run(s, "07:30");
        assertThat(digests(director, s.id)).singleElement().satisfies(r -> {
            assertThat(r.get("BODY").toString()).startsWith("Today: 1 late, 0 not arrived, 0 remote").contains(s.name(british))
                    .doesNotContain(s.name(american));
            assertThat(params(r).get(AttendanceLink.LATE)).isEqualTo("1");
            assertThat(params(r).get(AttendanceLink.LATE_NAMES)).isEqualTo(s.name(british));
        });
        for (String admin : users.findActiveAdminIds())
            assertThat(digests(admin, s.id)).singleElement().satisfies(r -> {
                assertThat(r.get("BODY").toString()).startsWith("Today: 1 late, 1 not arrived, 0 remote")
                        .contains(s.name(british)).contains(s.name(american));
                assertThat(params(r).get(AttendanceLink.NOT_ARRIVED)).isEqualTo("1");
            });

        run(s, "09:00");
        assertThat(digests(director, s.id)).as("once a day").hasSize(1);
        assertThat(alerts(director)).as("DIGEST: no immediate alert for the director").isEmpty();
    }

    @Test void the_directors_policy_immediate_or_off_takes_effect_on_the_next_run() {
        var s = school("policy");
        String teacher = s.teacher("t", "british"), director = s.manager("d", "british"), manager = s.manager("m", null);
        s.directManager(teacher, manager);
        s.policy("IMMEDIATE", "OFF");
        s.checkIn(teacher, "07:20", null);
        s.checkIn(director, "06:50", null); s.checkIn(manager, "06:50", null);
        run(s, "07:25");
        assertThat(alerts(manager)).hasSize(1);
        assertThat(alerts(director)).as("IMMEDIATE: the same alert").singleElement()
                .satisfies(r -> assertThat(params(r).get(AttendanceLink.TYPE)).isEqualTo(AttendanceLink.ALERT_LATE));
        for (String admin : users.findActiveAdminIds()) assertThat(rowsIn(admin, s.id)).as("Admin OFF").isEmpty();

        s.policy("OFF", "OFF");
        run(s, "08:00");
        assertThat(digests(director, s.id)).as("OFF: no digest either").isEmpty();
        for (String admin : users.findActiveAdminIds()) assertThat(rowsIn(admin, s.id)).isEmpty();
    }

    @Test void a_reminder_reaches_her_phone_in_her_language() throws Exception {
        var s = school("push");
        String teacher = s.teacher("t", "british");
        String token = PushProbe.token("attendance");
        PushProbe.register(mvc, "Bearer " + jwt.issue(teacher, teacher + "@ci16.test", "TEACHER", s.id).token(), token, "ar");
        run(s, "07:00");
        var sent = PushProbe.await(pushSender, token, 1);
        assertThat(sent).singleElement().satisfies(p -> {
            assertThat(p.message().getKind().name()).isEqualTo("ATTENDANCE_REMINDER");
            assertThat(p.message().getBody()).isEqualTo("لم تسجل حضورك بعد");
        });
    }

    // ---------------------------------------------------------------- isolation

    @Test void one_schools_run_writes_and_tells_nothing_in_another() {
        var a = school("iso-a");
        var b = school("iso-b");
        String teacherA = a.teacher("t", "british"), teacherB = b.teacher("t", "british");
        run(a, "08:00");
        assertThat(rows(teacherA)).isNotEmpty().allMatch(r -> a.id.equals(r.get("SCHOOL_ID")));
        assertThat(rows(teacherB)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_attendance_days WHERE school_id = ?", Integer.class, b.id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_attendance_alerts WHERE school_id = ?", Integer.class, b.id)).isZero();
        // And a school with the switch off is skipped by the full pass.
        var off = school("iso-off");
        off.flag(FlagKeys.STAFF_ATTENDANCE, false);
        String teacherOff = off.teacher("t", "british");
        clock.pinTo(SUNDAY.atTime(8, 0).atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant());
        job.run();
        assertThat(rows(teacherOff)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private int run(School s, String riyadhTime) {
        clock.pinTo(SUNDAY.atTime(LocalTime.parse(riyadhTime)).atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant());
        return job.run(s.id);
    }

    private List<Map<String, Object>> rows(String userId) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? AND kind LIKE 'attendance.%' ORDER BY created_at", userId);
    }

    /** A leader's alerts only: a manager is staff too, and her own check-in reminders are not what these tests read. */
    private List<Map<String, Object>> alerts(String userId) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? AND kind = 'attendance.alert' ORDER BY created_at", userId);
    }

    private List<Map<String, Object>> rowsIn(String userId, String schoolId) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? AND school_id = ? AND kind LIKE 'attendance.%'", userId, schoolId);
    }

    private List<Map<String, Object>> digests(String userId, String schoolId) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE user_id = ? AND school_id = ? AND kind = 'attendance.digest'", userId, schoolId);
    }

    private Map<String, Object> day(School s, String userId) {
        return jdbc.queryForMap("SELECT * FROM staff_attendance_days WHERE school_id = ? AND staff_id = ?", s.id, userId);
    }

    private static Map<String, String> params(Map<String, Object> row) { return AttendanceLink.INSTANCE.params((String) row.get("LINK")); }

    /** A school of the test's own, its staff named by the test so a row can never be mistaken for another test's. */
    private School school(String label) {
        String id = "ci16-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id); row.setCode(id.replace("-", "").toUpperCase(Locale.ROOT).substring(0, Math.min(20, id.replace("-", "").length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var s = new School(id);
        s.flag(FlagKeys.STAFF_ATTENDANCE, true);
        s.flag(FlagKeys.STAFF_MOBILE_APP, true);
        s.policy("DIGEST", "DIGEST");
        return s;
    }

    private final class School {
        final String id;
        School(String id) { this.id = id; }

        String name(String userId) { return users.findById(userId).orElseThrow().getDisplayName(); }

        String teacher(String label, String curriculum) {
            String userId = user(label, "TEACHER");
            var t = new TeacherEntity();
            t.setUserId(userId); t.setCurriculum(curriculum); t.setUpdatedAt(Instant.now());
            teachers.save(t);
            return userId;
        }

        /** A manager; with an academy, she is its director (a department row with no subject). */
        String manager(String label, String academy) {
            String userId = user(label, "MANAGERIAL");
            if (academy != null) {
                var scope = new StaffScopeEntity();
                scope.setId(UUID.randomUUID().toString()); scope.setSchoolId(id); scope.setUserId(userId);
                scope.setSubject(null); scope.setCurriculum(academy); scope.setCreatedAt(Instant.now());
                scopes.save(scope);
            }
            return userId;
        }

        /** Feature 20: a floor supervisor — staff of the school, and so nameable as a direct manager. */
        String supervisor(String label) { return user(label, quest.server.tenancy.FloorScope.ROLE); }

        /** A worker with an app account; answers the account's id, which is where her bell is. */
        String worker(String label) {
            String account = user(label + "-acct", "WORKER");
            var w = new quest.server.workers.Entities.WorkerEntity();
            w.setId(id + "-" + label); w.setSchoolId(id); w.setFullName("Worker " + label); w.setJob("caretaker");
            w.setActive(true); w.setUserId(account); w.setCreatedAt(Instant.now());
            workers.save(w);
            return account;
        }

        void directManager(String employee, String manager) {
            var row = new Entities.StaffAttendanceProfileEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id); row.setStaffKind("USER"); row.setStaffId(employee);
            row.setDirectManagerId(manager); row.setUpdatedAt(Instant.now());
            profiles.save(row);
        }

        void checkIn(String employee, String in, String out) {
            var row = new Entities.StaffCheckInEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id); row.setStaffKind("USER"); row.setStaffId(employee);
            row.setDay(SUNDAY);
            row.setCheckInAt(SUNDAY.atTime(LocalTime.parse(in)).atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant());
            row.setCheckInDistanceM(12);
            if (out != null) {
                row.setCheckOutAt(SUNDAY.atTime(LocalTime.parse(out)).atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant());
                row.setCheckOutDistanceM(14);
            }
            row.setMode("onsite");
            checkIns.save(row);
        }

        void holiday(LocalDate date) {
            var row = new Entities.AttendanceHolidayEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id); row.setName("Holiday"); row.setFromDate(date); row.setToDate(date);
            holidays.save(row);
        }

        void policy(String director, String admin) {
            var row = settings.findOneBySchoolId(id).orElseGet(Entities.AttendanceSettingsEntity::new);
            row.setSchoolId(id); row.setRadiusMeters(150); row.setGraceMinutes(10);
            row.setNotArrivedMinutes(60); row.setMissingCheckOutMinutes(60);
            row.setDirectorNotify(director); row.setAdminNotify(admin); row.setDigestTime(LocalTime.of(7, 30));
            row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
            settings.save(row);
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

        private String user(String label, String role) {
            String userId = id + "-" + label;
            var u = new UserEntity();
            u.setId(userId); u.setSchoolId(id); u.setEmail(userId + "@ci16.test"); u.setPasswordHash("x");
            u.setRole(role); u.setStatus("active"); u.setDisplayName("Staff " + label.toUpperCase(Locale.ROOT) + " " + id.substring(id.length() - 6));
            u.setCreatedAt(Instant.now().minusSeconds(label.hashCode() & 0xffff)); u.setUpdatedAt(Instant.now());
            users.save(u);
            return userId;
        }
    }
}
