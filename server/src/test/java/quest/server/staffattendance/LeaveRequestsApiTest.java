package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;
import quest.server.workers.WorkerRepository;

/**
 * Feature 34, end to end: a member of staff asks for leave or a short permission, her direct manager decides it, and
 * attendance stops calling her late or absent for it.
 *
 * <p>{@link #SCHOOL} is in Asia/Riyadh and keeps the default hours — 07:00–14:00, Sunday to Thursday — so a range
 * that spans a Friday and a Saturday is the spec's "school days only" case. October 2026: the 18th, 19th, 20th, 21st
 * and 22nd are Sunday to Thursday, the 23rd a Friday and the 24th a Saturday.
 *
 * <p><strong>Every scenario has its own employee and its own dates.</strong> A balance, an overlap and the board are
 * all per person and per day, and the suite shares one H2 database, so a test that borrowed another's teacher would
 * pass or fail on the order the files happened to run in. {@link #cleanUp} then deletes every row this file wrote:
 * a leaked `staff_attendance_days` row of this school used to break feature 25's counts in whichever file ran next.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaveRequestsApiTest extends ApiTestSupport {
    private static final String SCHOOL = "f34-school", OTHER = "f34-other";
    private static final String MANAGER = "f34-manager", OTHER_MANAGER = "f34-other-manager", ADMIN = "f34-admin";
    /** One teacher per scenario, each reporting to {@link #MANAGER}. */
    private static final String ANNUAL = "f34-annual", OVER = "f34-over", UNPAID = "f34-unpaid", SICK = "f34-sick",
            DECIDED = "f34-decided", CHAIN = "f34-chain", PAST = "f34-past", BOARD = "f34-board",
            EXCUSED = "f34-excused", PARTLY = "f34-partly", OVERLAP = "f34-overlap", CANCEL = "f34-cancel",
            REPORTED = "f34-reported", OFF = "f34-off";
    private static final List<String> TEACHERS = List.of(ANNUAL, OVER, UNPAID, SICK, DECIDED, CHAIN, PAST, BOARD,
            EXCUSED, PARTLY, OVERLAP, CANCEL, REPORTED, OFF);
    private static final double LAT = 24.7136, LON = 46.6753;
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired WorkerRepository workers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AttendanceSettingsRepository settings;
    @Autowired LeaveTypeRepository leaveTypes;
    @Autowired LeaveBalanceRepository leaveBalances;
    @Autowired StaffAttendanceProfileRepository profiles;
    @Autowired StaffCheckInRepository records;
    @Autowired StaffAttendanceDayRepository days;
    @Autowired StaffAttendanceRequestRepository requests;
    @Autowired StaffAttendanceAlertRepository alerts;
    @Autowired StaffAttendanceJob job;
    @Autowired AdminJwtService jwt;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll void fixture() {
        school(SCHOOL); school(OTHER);
        flags(SCHOOL, true); flags(OTHER, true);
        pin(SCHOOL); pin(OTHER);
        for (String id : TEACHERS) teacher(id, SCHOOL, "british");
        user(MANAGER, SCHOOL, "MANAGERIAL");
        user(OTHER_MANAGER, SCHOOL, "MANAGERIAL");
        user(ADMIN, null, "ADMIN");
        for (String id : TEACHERS) directManager(id, MANAGER);
        leaveSettings(SCHOOL);
    }

    @AfterEach void unpin() { clock.release(); }

    /** Nothing of this school outlives the file: the suite's H2 database is shared by every other test. */
    @AfterAll void cleanUp() {
        for (String table : List.of("staff_attendance_requests", "staff_attendance_days", "staff_attendance_alerts",
                "staff_check_ins", "staff_check_in_attempts", "staff_attendance_devices", "leave_balances",
                "leave_types", "attendance_settings", "staff_attendance_profiles"))
            jdbc.update("delete from " + table + " where school_id in (?, ?)", SCHOOL, OTHER);
        jdbc.update("delete from notifications where user_id like 'f34-%'");
        jdbc.update("delete from attachments where school_id in (?, ?)", SCHOOL, OTHER);
    }

    // ---------------------------------------------------------------- the Admin's Leave card

    @Test void the_admin_sets_the_types_the_balances_the_document_rule_the_chain_and_the_permission_limit() throws Exception {
        var page = ok(scoped(get("/admin/attendance-settings"), admin(), SCHOOL));
        assertThat(page.get("leaveChain").asText()).isEqualTo("MANAGER");
        assertThat(page.get("permissionMaxMinutes").asInt()).isEqualTo(180);
        var types = page.get("leaveTypes");
        assertThat(types).hasSize(5);
        assertThat(typeOf(page, "SICK").get("documentAfterDays").asInt()).as("the document rule").isEqualTo(2);
        assertThat(typeOf(page, "EMERGENCY").get("enabled").asBoolean()).as("the school turned it off").isFalse();
        assertThat(balanceOf(page, "ANNUAL", "TEACHER")).isEqualTo(5);
        assertThat(balanceOf(page, "ANNUAL", "WORKER")).as("a different allowance per category").isEqualTo(3);
        assertThat(balanceOf(page, "UNPAID", "TEACHER")).as("unpaid has no balance at all").isNull();

        // Only the Admin writes the card; a manager reads the rules her people are held to and changes nothing.
        ok(bearer(get("/admin/attendance-settings"), token(MANAGER, "MANAGERIAL")));
        mvc.perform(bearer(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON)
                .content(settingsBody("MANAGER", 180)), token(MANAGER, "MANAGERIAL"))).andExpect(status().isForbidden());

        mvc.perform(scoped(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON)
                .content(settingsBody("WHENEVER", 180)), admin(), SCHOOL)).andExpect(status().isBadRequest());
        mvc.perform(scoped(put("/admin/attendance-settings").contentType(MediaType.APPLICATION_JSON)
                .content(settingsBody("MANAGER", 0)), admin(), SCHOOL)).andExpect(status().isBadRequest());
        leaveSettings(SCHOOL);                                                   // the refused writes changed nothing
    }

    // ---------------------------------------------------------------- the days a leave uses, and the balance

    @Test void a_leave_counts_the_school_days_of_her_schedule_and_spends_that_many_of_the_balance() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        // Wednesday the 21st to Sunday the 25th: the Friday and the Saturday inside it are not deducted.
        var preview = ok(bearer(get("/staff/attendance/leave/preview?leaveType=ANNUAL&from=2026-10-21&to=2026-10-25"),
                token(ANNUAL, "TEACHER")));
        assertThat(preview.get("days").asInt()).isEqualTo(3);
        assertThat(dates(preview.get("dates"))).containsExactly("2026-10-21", "2026-10-22", "2026-10-25");
        assertThat(preview.get("allowanceDays").asInt()).isEqualTo(5);
        assertThat(preview.get("remainingDays").asInt()).isEqualTo(5);
        assertThat(preview.get("remainingAfter").asInt()).isEqualTo(2);
        assertThat(preview.get("documentRequired").asBoolean()).isFalse();

        var created = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("ANNUAL", "2026-10-21", "2026-10-25", "Family", null)), token(ANNUAL, "TEACHER")));
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("stage").asText()).isEqualTo("MANAGER");
        assertThat(created.get("days").asInt()).isEqualTo(3);
        assertThat(created.get("toDate").asText()).isEqualTo("2026-10-25");
        assertThat(balance(ANNUAL, "ANNUAL").get("usedDays").asInt()).as("a pending leave spends nothing").isZero();

        approve(created.get("id").asText(), token(MANAGER, "MANAGERIAL"));
        var row = balance(ANNUAL, "ANNUAL");
        assertThat(row.get("usedDays").asInt()).isEqualTo(3);
        assertThat(row.get("remainingDays").asInt()).isEqualTo(2);
        assertThat(ok(bearer(get("/staff/attendance/leave/balance"), token(ANNUAL, "TEACHER"))).get("year").asInt())
                .as("the calendar year of the school's own zone").isEqualTo(2026);
    }

    @Test void a_leave_beyond_the_balance_is_refused_and_a_type_with_no_balance_never_is() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        // Five of five days: the 19th to the 23rd is Monday to Friday, so four school days — then two more is six.
        approve(created(OVER, leave("ANNUAL", "2026-10-26", "2026-10-29", "Away", null)), token(MANAGER, "MANAGERIAL"));
        assertThat(balance(OVER, "ANNUAL").get("remainingDays").asInt()).isEqualTo(1);

        var refused = mvc.perform(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("ANNUAL", "2026-11-02", "2026-11-03", "More", null)), token(OVER, "TEACHER")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("over_balance");

        // Unpaid has no row in `leave_balances`, which is not a zero balance: nothing is refused on it.
        var unpaid = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("UNPAID", "2026-11-02", "2026-11-06", "No pay", null)), token(UNPAID, "TEACHER")));
        assertThat(unpaid.get("days").asInt()).as("Monday to Friday is four school days").isEqualTo(4);
        assertThat(balance(UNPAID, "UNPAID").hasNonNull("remainingDays")).isFalse();

        // A type the school turned off is not a type she can ask for.
        mvc.perform(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("EMERGENCY", "2026-11-09", null, "Urgent", null)), token(UNPAID, "TEACHER")))
                .andExpect(status().isBadRequest());
    }

    @Test void a_sick_leave_longer_than_the_rule_allows_cannot_be_sent_without_a_document() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        // SICK asks for a document after two days; the 19th to the 21st is three.
        mvc.perform(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("SICK", "2026-10-19", "2026-10-21", "Flu", null)), token(SICK, "TEACHER")))
                .andExpect(status().isBadRequest());
        // Two days needs none.
        ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("SICK", "2026-11-16", "2026-11-17", "Flu", null)), token(SICK, "TEACHER")));
        // Three with the doctor's note does.
        String attachment = upload(token(SICK, "TEACHER"));
        var withNote = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("SICK", "2026-11-23", "2026-11-25", "Flu", attachment)), token(SICK, "TEACHER")));
        assertThat(withNote.get("attachment").get("id").asText()).isEqualTo(attachment);
    }

    // ---------------------------------------------------------------- who decides, and the reason

    @Test void only_her_direct_manager_or_the_admin_decides_and_a_rejection_needs_a_reason() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        String id = created(DECIDED, leave("ANNUAL", "2026-10-26", "2026-10-27", "Trip", null));

        // The push carries the request's id and the new link type, which is what the released Android build reads.
        assertThat(notifications(MANAGER, "attendance.request", "request=" + id)).isEqualTo(1);
        assertThat(link(MANAGER, "attendance.request")).contains("attendance=leave_request").contains("request=" + id);
        assertThat(notifications(OTHER_MANAGER, "attendance.request", "request=" + id)).isZero();

        mvc.perform(bearer(post("/attendance/requests/" + id + "/approve"), token(OTHER_MANAGER, "MANAGERIAL"))).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/attendance/requests/" + id + "/approve"), token(DECIDED, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(post("/attendance/requests/" + id + "/approve").header("Authorization", PARENT)).andExpect(status().isForbidden());

        // A rejection with no body at all, and one with an empty reason, are both refused.
        mvc.perform(bearer(post("/attendance/requests/" + id + "/reject"), token(MANAGER, "MANAGERIAL"))).andExpect(status().isBadRequest());
        mvc.perform(bearer(post("/attendance/requests/" + id + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}"), token(MANAGER, "MANAGERIAL"))).andExpect(status().isBadRequest());

        var rejected = ok(bearer(post("/attendance/requests/" + id + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Exams that week\"}"), token(MANAGER, "MANAGERIAL")));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectReason").asText()).isEqualTo("Exams that week");
        assertThat(rejected.hasNonNull("stage")).as("a decided request waits for nobody").isFalse();
        assertThat(notifications(DECIDED, "attendance.decision", "request=" + id)).as("she is told either way").isEqualTo(1);
        assertThat(jdbc.queryForObject("select body from notifications where user_id = ? and kind = 'attendance.decision'",
                String.class, DECIDED)).contains("Exams that week");
        assertThat(balance(DECIDED, "ANNUAL").get("usedDays").asInt()).as("a rejected leave spends nothing").isZero();

        // A remote day, which feature 18 released, is still rejected with no body — the push's bare button.
        String remote = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"REMOTE_DAY\",\"date\":\"2026-10-26\",\"reason\":\"Plumber\"}"), token(DECIDED, "TEACHER")))
                .get("id").asText();
        assertThat(ok(bearer(post("/attendance/requests/" + remote + "/reject"), token(MANAGER, "MANAGERIAL")))
                .get("status").asText()).isEqualTo("REJECTED");
    }

    @Test void with_the_two_step_chain_the_request_counts_only_once_the_admin_has_approved_it() throws Exception {
        chain(SCHOOL, "MANAGER_THEN_ADMIN");
        try {
            clock.pinTo(at("2026-10-19", "09:00"));
            String id = created(CHAIN, leave("ANNUAL", "2026-10-20", "2026-10-20", "One day", null));

            var afterManager = approve(id, token(MANAGER, "MANAGERIAL"));
            assertThat(afterManager.get("status").asText()).as("step one of two").isEqualTo("PENDING");
            assertThat(afterManager.get("stage").asText()).isEqualTo("ADMIN");
            assertThat(balance(CHAIN, "ANNUAL").get("usedDays").asInt()).as("it counts for nothing yet").isZero();
            assertThat(boardStatus(CHAIN)).as("and excuses nothing yet").isNotEqualTo("ON_LEAVE");
            assertThat(notifications(ADMIN, "attendance.request", "request=" + id)).as("the Admin is told the same way").isEqualTo(1);

            // Her manager has decided her part and sees nothing left to do with it.
            assertThat(ids(ok(bearer(get("/attendance/requests?status=PENDING"), token(MANAGER, "MANAGERIAL"))))).doesNotContain(id);
            assertThat(ids(ok(scoped(get("/attendance/requests?status=PENDING"), admin(), SCHOOL)))).contains(id);
            mvc.perform(bearer(post("/attendance/requests/" + id + "/approve"), token(MANAGER, "MANAGERIAL"))).andExpect(status().isForbidden());

            var afterAdmin = approve(id, admin());
            assertThat(afterAdmin.get("status").asText()).isEqualTo("APPROVED");
            assertThat(balance(CHAIN, "ANNUAL").get("usedDays").asInt()).isEqualTo(1);
        } finally {
            chain(SCHOOL, "MANAGER");
        }
    }

    @Test void a_request_for_a_past_date_waits_for_the_admin_even_with_direct_manager_only() throws Exception {
        clock.pinTo(at("2026-10-22", "09:00"));
        var created = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("ANNUAL", "2026-10-19", "2026-10-19", "I was ill", null)), token(PAST, "TEACHER")));
        String id = created.get("id").asText();
        assertThat(created.get("stage").asText()).as("a day already recorded is the Admin's to rewrite").isEqualTo("ADMIN");
        assertThat(notifications(ADMIN, "attendance.request", "request=" + id)).isEqualTo(1);
        assertThat(notifications(MANAGER, "attendance.request", "request=" + id)).isZero();
        mvc.perform(bearer(post("/attendance/requests/" + id + "/approve"), token(MANAGER, "MANAGERIAL"))).andExpect(status().isForbidden());
        assertThat(approve(id, admin()).get("status").asText()).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- what an approved leave does to attendance

    @Test void an_approved_leave_is_on_leave_on_every_path_and_silences_the_days_alerts() throws Exception {
        // She was already recorded absent on the 19th before the leave was approved.
        clock.pinTo(at("2026-10-19", "15:00"));
        assertThat(job.run(SCHOOL)).isNotNegative();
        assertThat(days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", BOARD, LocalDate.parse("2026-10-19")))
                .as("feature 16 wrote the day").isPresent();

        clock.pinTo(at("2026-10-20", "09:00"));
        String id = created(BOARD, leave("ANNUAL", "2026-10-19", "2026-10-20", "Ill", null));
        approve(id, admin());                                                    // a past date: the Admin's

        // 1. The board, which evaluates on read.
        assertThat(boardStatus(BOARD)).isEqualTo("ON_LEAVE");
        // 2. One employee's day and her month calendar.
        var month = ok(scoped(get("/staff-attendance/employees/USER/" + BOARD + "?day=2026-10-20"), admin(), SCHOOL));
        assertThat(calendarDay(month, "2026-10-19").get("status").asText()).isEqualTo("ON_LEAVE");
        assertThat(calendarDay(month, "2026-10-20").get("status").asText()).isEqualTo("ON_LEAVE");
        // 3. Her own "My record".
        var mine = ok(bearer(get("/staff/attendance/month?month=2026-10"), token(BOARD, "TEACHER")));
        assertThat(day(mine, "2026-10-19").get("status").asText()).as("never ABSENT on a day of leave").isEqualTo("ON_LEAVE");
        // 4. The monthly report.
        // October 2026 has fourteen school days up to the 20th; the two of leave are not among the working days, and
        // she is absent on the twelve that are left, because this teacher never checked in at all.
        var reportRow = reportRow(BOARD, "2026-10");
        assertThat(reportRow.get("leaveDays").asInt()).isEqualTo(2);
        assertThat(reportRow.get("workingDays").asInt()).as("a day of leave is not a working day").isEqualTo(12);
        assertThat(reportRow.get("absentDays").asInt()).as("and is never one she missed").isEqualTo(12);
        // 5. Feature 16's own rows: the absent the approval cleared, and no row written on the 20th.
        assertThat(days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", BOARD, LocalDate.parse("2026-10-19")))
                .as("approving recomputes the day, as a correction does").isEmpty();

        // 6. The job sends no alert about a day of leave.
        int before = alertCount(BOARD);
        clock.pinTo(at("2026-10-20", "15:00"));
        job.run(SCHOOL);
        assertThat(alertCount(BOARD)).as("nothing new about her").isEqualTo(before);
        assertThat(days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", BOARD, LocalDate.parse("2026-10-20"))).isEmpty();
    }

    @Test void an_approved_permission_excuses_the_minutes_inside_its_window_and_nothing_outside_it() throws Exception {
        // The spec's case: 07:00–09:00 approved, she checks in at 08:30, which stops being late.
        clock.pinTo(at("2026-10-21", "08:30"));
        checkInAt(EXCUSED, "2026-10-21", "08:30", null);
        assertThat(boardRow(EXCUSED).get("status").asText()).as("late until the permission is approved").isEqualTo("LATE");

        String id = created(EXCUSED, permission("2026-10-21", "07:00", "09:00", "Clinic"));
        var approved = approve(id, token(MANAGER, "MANAGERIAL"));
        assertThat(approved.get("minutes").asInt()).isEqualTo(120);

        var row = boardRow(EXCUSED);
        assertThat(row.get("status").asText()).isEqualTo("PRESENT");
        assertThat(row.hasNonNull("lateMinutes")).isFalse();
        var written = days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", EXCUSED, LocalDate.parse("2026-10-21")).orElseThrow();
        assertThat(written.getLateMinutes()).as("the approval recomputed the recorded day").isNull();
        assertThat(written.getExcusedMinutes()).isEqualTo(90);
        var reported = reportRow(EXCUSED, "2026-10");
        assertThat(reported.get("excusedMinutes").asInt()).isEqualTo(90);
        assertThat(reported.get("permissionCount").asInt()).isEqualTo(1);
        assertThat(reported.get("lateCount").asInt()).isZero();
    }

    @Test void a_permission_leaves_the_minutes_it_does_not_cover_late_and_an_early_leave_outside_it_untouched() throws Exception {
        clock.pinTo(at("2026-10-22", "14:30"));
        // In at 09:40 with a 07:00–09:00 permission: 40 of the 160 minutes are hers. Out at 13:00: an hour early,
        // which the morning's window does not touch.
        checkInAt(PARTLY, "2026-10-22", "09:40", "13:00");
        approve(created(PARTLY, permission("2026-10-22", "07:00", "09:00", "Clinic")), token(MANAGER, "MANAGERIAL"));

        var written = days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", PARTLY, LocalDate.parse("2026-10-22")).orElseThrow();
        assertThat(written.getLateMinutes()).as("160 minutes after the start, 120 of them excused").isEqualTo(40);
        assertThat(written.getEarlyLeaveMinutes()).as("the window covered none of the afternoon").isEqualTo(60);
        assertThat(written.getExcusedMinutes()).isEqualTo(120);
        assertThat(boardRow(PARTLY).get("status").asText()).isEqualTo("LATE");
    }

    // ---------------------------------------------------------------- the guardrails

    @Test void an_overlapping_request_is_refused_whichever_way_round_it_is() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        approve(created(OVERLAP, leave("ANNUAL", "2026-11-02", "2026-11-04", "Away", null)), token(MANAGER, "MANAGERIAL"));

        // Leave over leave.
        assertThat(conflict(OVERLAP, leave("UNPAID", "2026-11-04", "2026-11-05", "Again", null))).isEqualTo("overlaps");
        // A permission inside a leave day.
        assertThat(conflict(OVERLAP, permission("2026-11-03", "07:00", "09:00", "Clinic"))).isEqualTo("overlaps");
        // A permission over a permission, but not one beside it on the same day.
        approve(created(OVERLAP, permission("2026-11-09", "07:00", "09:00", "Clinic")), token(MANAGER, "MANAGERIAL"));
        assertThat(conflict(OVERLAP, permission("2026-11-09", "08:00", "10:00", "Again"))).isEqualTo("overlaps");
        ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(permission("2026-11-09", "09:00", "10:00", "Later")), token(OVERLAP, "TEACHER")));
        // A permission longer than the school allows.
        mvc.perform(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(permission("2026-11-10", "07:00", "11:00", "Too long")), token(OVERLAP, "TEACHER")))
                .andExpect(status().isBadRequest());
    }

    @Test void a_pending_request_is_cancelled_by_its_author_and_by_nobody_else() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        String id = created(CANCEL, leave("ANNUAL", "2026-11-02", "2026-11-03", "Maybe", null));
        mvc.perform(bearer(post("/staff/attendance/requests/" + id + "/cancel"), token(OVERLAP, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/staff/attendance/requests/" + id + "/cancel"), token(MANAGER, "MANAGERIAL"))).andExpect(status().isForbidden());

        assertThat(ok(bearer(post("/staff/attendance/requests/" + id + "/cancel"), token(CANCEL, "TEACHER")))
                .get("status").asText()).isEqualTo("CANCELLED");
        // Cancelled is decided: nobody settles it afterwards, and it holds no days against her any more.
        mvc.perform(bearer(post("/attendance/requests/" + id + "/approve"), token(MANAGER, "MANAGERIAL"))).andExpect(status().isConflict());
        mvc.perform(bearer(post("/staff/attendance/requests/" + id + "/cancel"), token(CANCEL, "TEACHER"))).andExpect(status().isConflict());
        ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content(leave("ANNUAL", "2026-11-02", "2026-11-03", "After all", null)), token(CANCEL, "TEACHER")));

        // An approved one is not cancelled from the app either; that is a decision, not a withdrawal.
        String approved = created(CANCEL, leave("UNPAID", "2026-11-16", "2026-11-16", "Sure", null));
        approve(approved, token(MANAGER, "MANAGERIAL"));
        mvc.perform(bearer(post("/staff/attendance/requests/" + approved + "/cancel"), token(CANCEL, "TEACHER"))).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- the two reports

    @Test void the_balance_and_the_monthly_requests_export_as_csv_and_as_excel() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        approve(created(REPORTED, leave("ANNUAL", "2026-10-26", "2026-10-27", "Trip", null)), token(MANAGER, "MANAGERIAL"));
        String rejected = created(REPORTED, permission("2026-10-28", "07:00", "08:00", "Clinic"));
        ok(bearer(post("/attendance/requests/" + rejected + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Class at that hour\"}"), token(MANAGER, "MANAGERIAL")));

        var balances = ok(scoped(get("/staff-attendance/leave/balances?year=2026"), admin(), SCHOOL));
        assertThat(balances.get("year").asInt()).isEqualTo(2026);
        assertThat(dates(balances.get("types"))).as("the types the school offers, in order")
                .containsExactly("ANNUAL", "SICK", "UNPAID", "OTHER");
        var hers = reportRowOf(balances, REPORTED);
        assertThat(cell(hers, "ANNUAL").get("usedDays").asInt()).isEqualTo(2);
        assertThat(cell(hers, "ANNUAL").get("remainingDays").asInt()).isEqualTo(3);
        assertThat(cell(hers, "UNPAID").hasNonNull("remainingDays")).isFalse();

        var csv = file(get("/staff-attendance/leave/balances.csv"), "staff-leave-balances-2026.csv");
        assertThat(csv.split("\n")[0]).contains("ANNUAL allowance", "ANNUAL used", "ANNUAL remaining", "الموظف");
        assertThat(csv).contains(REPORTED + ",TEACHER,british,5,2,3");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes(get("/staff-attendance/leave/balances.xlsx"))))) {
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Employee / الموظف");
        }

        var monthly = ok(scoped(get("/staff-attendance/leave/requests?month=2026-10"), admin(), SCHOOL));
        var rows = monthly.get("rows");
        assertThat(rowsOf(rows, REPORTED)).hasSize(2);
        var leaveRow = requestRowOf(rows, REPORTED, "LEAVE");
        assertThat(leaveRow.get("leaveType").asText()).isEqualTo("ANNUAL");
        assertThat(leaveRow.get("days").asInt()).isEqualTo(2);
        assertThat(leaveRow.get("toDate").asText()).isEqualTo("2026-10-27");
        assertThat(leaveRow.get("decidedBy").asText()).isEqualTo(MANAGER);
        var permissionRow = requestRowOf(rows, REPORTED, "PERMISSION");
        assertThat(permissionRow.get("status").asText()).isEqualTo("REJECTED");
        assertThat(permissionRow.get("rejectReason").asText()).isEqualTo("Class at that hour");
        assertThat(permissionRow.get("minutes").asInt()).isEqualTo(60);

        var requestsCsv = file(get("/staff-attendance/leave/requests.csv"), "staff-requests-2026-10.csv");
        assertThat(requestsCsv.split("\n")[0]).contains("Leave type", "Rejection reason", "الموظف");
        assertThat(requestsCsv).contains("Class at that hour");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes(get("/staff-attendance/leave/requests.xlsx"))))) {
            assertThat(book.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short) LeaveExports.REQUEST_HEADER.size());
        }

        // A manager reads her own people; a teacher who leads nobody reads only herself, and never the Admin's view.
        assertThat(names(ok(bearer(get("/staff-attendance/leave/balances"), token(MANAGER, "MANAGERIAL"))))).contains(REPORTED);
        assertThat(names(ok(bearer(get("/staff-attendance/leave/balances"), token(OTHER_MANAGER, "MANAGERIAL"))))).doesNotContain(REPORTED);
        mvc.perform(get("/staff-attendance/leave/balances").header("Authorization", PARENT)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- the switch, and the other school

    @Test void the_switch_off_answers_404_and_another_schools_requests_are_invisible() throws Exception {
        clock.pinTo(at("2026-10-19", "09:00"));
        String mine = created(OFF, leave("ANNUAL", "2026-11-23", "2026-11-24", "Away", null));
        // Another school's manager sees nothing of it, whichever header she sends.
        teacher("f34-other-teacher", OTHER, "british");
        assertThat(ids(ok(scoped(get("/attendance/requests"), admin(), OTHER)))).doesNotContain(mine);

        setSchoolFlag(SCHOOL, FlagKeys.STAFF_LEAVE, false);
        try {
            mvc.perform(bearer(get("/staff/attendance/leave/balance"), token(OFF, "TEACHER"))).andExpect(status().isNotFound());
            mvc.perform(bearer(get("/staff/attendance/leave/preview?leaveType=ANNUAL&from=2026-11-23"), token(OFF, "TEACHER")))
                    .andExpect(status().isNotFound());
            mvc.perform(bearer(post("/staff/attendance/requests/" + mine + "/cancel"), token(OFF, "TEACHER"))).andExpect(status().isNotFound());
            mvc.perform(scoped(get("/staff-attendance/leave/balances"), admin(), SCHOOL)).andExpect(status().isNotFound());
            mvc.perform(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                    .content(leave("ANNUAL", "2026-11-30", null, "Away", null)), token(OFF, "TEACHER")))
                    .andExpect(status().isNotFound());
            // Feature 18's own types are untouched by feature 34's switch.
            ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"REMOTE_DAY\",\"date\":\"2026-11-30\",\"reason\":\"Plumber\"}"), token(OFF, "TEACHER")));
        } finally {
            setSchoolFlag(SCHOOL, FlagKeys.STAFF_LEAVE, true);
        }
    }

    // ---------------------------------------------------------------- requests and reads

    private String created(String userId, String body) throws Exception {
        return ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON).content(body),
                token(userId, "TEACHER"))).get("id").asText();
    }

    private String conflict(String userId, String body) throws Exception {
        var result = mvc.perform(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON).content(body),
                token(userId, "TEACHER"))).andExpect(status().isConflict()).andReturn();
        return json(result).get("code").asText();
    }

    private JsonNode approve(String id, String token) throws Exception {
        return ok(scoped(post("/attendance/requests/" + id + "/approve"), token, SCHOOL));
    }

    private JsonNode balance(String userId, String type) throws Exception {
        var page = ok(bearer(get("/staff/attendance/leave/balance"), token(userId, "TEACHER")));
        for (var row : page.get("types")) if (type.equals(row.get("type").asText())) return row;
        throw new AssertionError(type + " is not in the balance");
    }

    private JsonNode boardRow(String userId) throws Exception {
        var board = ok(scoped(get("/staff-attendance/board"), admin(), SCHOOL));
        for (var row : board.get("rows")) if (userId.equals(row.get("id").asText())) return row;
        throw new AssertionError(userId + " is not on the board");
    }

    private String boardStatus(String userId) throws Exception { return boardRow(userId).get("status").asText(); }

    private JsonNode reportRow(String userId, String month) throws Exception {
        var report = ok(scoped(get("/staff-attendance/report?month=" + month), admin(), SCHOOL));
        return reportRowOf(report, userId);
    }

    private static JsonNode reportRowOf(JsonNode report, String userId) {
        for (var row : report.get("rows")) if (userId.equals(row.get("id").asText())) return row;
        throw new AssertionError(userId + " is not in the report");
    }

    private static JsonNode requestRowOf(JsonNode rows, String userId, String type) {
        for (var row : rows) if (userId.equals(row.get("staffId").asText()) && type.equals(row.get("type").asText())) return row;
        throw new AssertionError(userId + " has no " + type + " row");
    }

    private static List<JsonNode> rowsOf(JsonNode rows, String userId) {
        var out = new java.util.ArrayList<JsonNode>();
        for (var row : rows) if (userId.equals(row.get("staffId").asText())) out.add(row);
        return out;
    }

    private static JsonNode cell(JsonNode row, String type) {
        for (var c : row.get("balances")) if (type.equals(c.get("type").asText())) return c;
        throw new AssertionError(type + " is not among the balances");
    }

    private static List<String> names(JsonNode report) {
        var out = new java.util.ArrayList<String>();
        for (var row : report.get("rows")) out.add(row.get("id").asText());
        return out;
    }

    private String upload(String token) throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png);
        var file = new MockMultipartFile("file", "note.png", MediaType.IMAGE_PNG_VALUE, png.toByteArray());
        return ok(bearer(multipart("/staff/attendance/attachments").file(file), token)).get("id").asText();
    }

    private String file(MockHttpServletRequestBuilder request, String filename) throws Exception {
        var result = mvc.perform(scoped(request, admin(), SCHOOL)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains(filename);
        byte[] body = result.getResponse().getContentAsByteArray();
        return new String(body, 3, body.length - 3, StandardCharsets.UTF_8);      // past the UTF-8 BOM
    }

    private byte[] bytes(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(scoped(request, admin(), SCHOOL)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();
    }

    // ---------------------------------------------------------------- bodies

    private static String leave(String type, String from, String to, String reason, String attachmentId) {
        return "{\"type\":\"LEAVE\",\"leaveType\":\"" + type + "\",\"date\":\"" + from + "\""
                + (to == null ? "" : ",\"toDate\":\"" + to + "\"")
                + (attachmentId == null ? "" : ",\"attachmentId\":\"" + attachmentId + "\"")
                + ",\"reason\":\"" + reason + "\"}";
    }

    private static String permission(String date, String from, String to, String reason) {
        return "{\"type\":\"PERMISSION\",\"date\":\"" + date + "\",\"fromTime\":\"" + from + "\",\"toTime\":\"" + to
                + "\",\"reason\":\"" + reason + "\"}";
    }

    /** The Leave card as this file's fixture sets it, with the chain and the permission limit a test varies. */
    private static String settingsBody(String chain, int permissionMaxMinutes) {
        return "{\"leaveChain\":\"" + chain + "\",\"permissionMaxMinutes\":" + permissionMaxMinutes + ","
                + "\"leaveTypes\":["
                + "{\"type\":\"ANNUAL\",\"enabled\":true},"
                + "{\"type\":\"SICK\",\"enabled\":true,\"documentAfterDays\":2},"
                + "{\"type\":\"EMERGENCY\",\"enabled\":false},"
                + "{\"type\":\"UNPAID\",\"enabled\":true},"
                + "{\"type\":\"OTHER\",\"enabled\":true}],"
                + "\"leaveBalances\":["
                + "{\"type\":\"ANNUAL\",\"category\":\"TEACHER\",\"yearlyDays\":5},"
                + "{\"type\":\"ANNUAL\",\"category\":\"WORKER\",\"yearlyDays\":3},"
                + "{\"type\":\"SICK\",\"category\":\"TEACHER\",\"yearlyDays\":10}],"
                + "\"latitude\":" + LAT + ",\"longitude\":" + LON + ",\"radiusMeters\":200}";
    }

    private static JsonNode typeOf(JsonNode page, String type) {
        for (var row : page.get("leaveTypes")) if (type.equals(row.get("type").asText())) return row;
        throw new AssertionError(type + " is not on the page");
    }

    private static Integer balanceOf(JsonNode page, String type, String category) {
        for (var row : page.get("leaveBalances"))
            if (type.equals(row.get("type").asText()) && category.equals(row.get("category").asText()))
                return row.get("yearlyDays").asInt();
        return null;
    }

    private static List<String> dates(JsonNode array) {
        var out = new java.util.ArrayList<String>();
        for (var value : array) out.add(value.asText());
        return out;
    }

    private static List<String> ids(JsonNode list) {
        var out = new java.util.ArrayList<String>();
        for (var r : list.get("requests")) out.add(r.get("id").asText());
        return out;
    }

    private static JsonNode calendarDay(JsonNode employeeDay, String date) {
        for (var row : employeeDay.get("calendar")) if (date.equals(row.get("date").asText())) return row;
        throw new AssertionError(date + " is not in the calendar");
    }

    private static JsonNode day(JsonNode month, String date) {
        for (var row : month.get("days")) if (date.equals(row.get("date").asText())) return row;
        throw new AssertionError(date + " is not in the month");
    }

    private int notifications(String userId, String kind, String fragment) {
        Integer n = jdbc.queryForObject("select count(*) from notifications where user_id = ? and kind = ? and link like ?",
                Integer.class, userId, kind, "%" + fragment + "%");
        return n == null ? 0 : n;
    }

    private String link(String userId, String kind) {
        return jdbc.queryForObject("select link from notifications where user_id = ? and kind = ? order by created_at desc limit 1",
                String.class, userId, kind);
    }

    private int alertCount(String staffId) {
        Integer n = jdbc.queryForObject("select count(*) from staff_attendance_alerts where school_id = ? and staff_id = ?",
                Integer.class, SCHOOL, staffId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- the fixture

    /** A recorded day, written as feature 15 would have, so a permission has something to excuse. */
    private void checkInAt(String staffId, String date, String in, String out) {
        LocalDate day = LocalDate.parse(date);
        var row = records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", staffId, day)
                .orElseGet(Entities.StaffCheckInEntity::new);
        if (row.getId() == null) {
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(SCHOOL);
            row.setStaffKind("USER"); row.setStaffId(staffId); row.setDay(day); row.setCheckInDistanceM(10);
            row.setMode("onsite");
        }
        row.setCheckInAt(at(date, in));
        row.setCheckOutAt(out == null ? null : at(date, out));
        records.save(row);
    }

    private static Instant at(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(RIYADH).toInstant();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return json(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private static MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return builder.header("Authorization", "Bearer " + token).header(TenantContext.HEADER, schoolId);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private String admin() { return jwt.issue(ADMIN, ADMIN + "@f34.test", "ADMIN", null).token(); }

    private String token(String userId, String role) { return jwt.issue(userId, userId + "@f34.test", role, SCHOOL).token(); }

    private void school(String id) {
        var s = schools.findById(id).orElseGet(SchoolEntity::new);
        s.setId(id); s.setName(id); s.setCode(id.replace("-", "").toUpperCase(Locale.ROOT));
        s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
        s.setStatus("active"); s.setTimezone("Asia/Riyadh");
        if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private void flags(String schoolId, boolean leave) {
        flag(schoolId, FlagKeys.STAFF_ATTENDANCE, true);
        flag(schoolId, FlagKeys.STAFF_MOBILE_APP, true);
        flag(schoolId, FlagKeys.STAFF_LEAVE, leave);
        featureFlags.invalidate(schoolId);
    }

    private void flag(String schoolId, String key, boolean enabled) {
        var row = schoolFlags.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
    }

    private void pin(String schoolId) {
        var row = settings.findOneBySchoolId(schoolId).orElseGet(Entities.AttendanceSettingsEntity::new);
        row.setSchoolId(schoolId); row.setLatitude(LAT); row.setLongitude(LON);
        row.setRadiusMeters(200); row.setGraceMinutes(10);
        row.setNotArrivedMinutes(60); row.setMissingCheckOutMinutes(60);
        row.setDirectorNotify("OFF"); row.setAdminNotify("OFF"); row.setDigestTime(LocalTime.of(7, 30));
        row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        settings.save(row);
    }

    /** The Leave card, written straight to the two tables so the fixture does not depend on the Admin's route. */
    private void leaveSettings(String schoolId) {
        chain(schoolId, "MANAGER");
        leaveType(schoolId, "ANNUAL", true, null);
        leaveType(schoolId, "SICK", true, 2);
        leaveType(schoolId, "EMERGENCY", false, null);
        leaveType(schoolId, "UNPAID", true, null);
        leaveType(schoolId, "OTHER", true, null);
        leaveBalance(schoolId, "ANNUAL", "TEACHER", 5);
        leaveBalance(schoolId, "ANNUAL", "WORKER", 3);
        leaveBalance(schoolId, "SICK", "TEACHER", 10);
    }

    private void chain(String schoolId, String chain) {
        var row = settings.findOneBySchoolId(schoolId).orElseThrow();
        row.setLeaveChain(chain); row.setPermissionMaxMinutes(180); row.setUpdatedAt(Instant.now());
        settings.save(row);
    }

    private void leaveType(String schoolId, String type, boolean enabled, Integer documentAfterDays) {
        var row = leaveTypes.findOneBySchoolIdAndLeaveType(schoolId, type).orElseGet(Entities.LeaveTypeEntity::new);
        if (row.getId() == null) { row.setId("f34-type-" + schoolId + "-" + type); row.setSchoolId(schoolId); row.setLeaveType(type); }
        row.setEnabled(enabled); row.setDocumentAfterDays(documentAfterDays); row.setUpdatedAt(Instant.now());
        leaveTypes.save(row);
    }

    private void leaveBalance(String schoolId, String type, String category, int yearlyDays) {
        var row = leaveBalances.findOneBySchoolIdAndLeaveTypeAndCategory(schoolId, type, category)
                .orElseGet(Entities.LeaveBalanceEntity::new);
        if (row.getId() == null) {
            row.setId("f34-balance-" + schoolId + "-" + type + "-" + category);
            row.setSchoolId(schoolId); row.setLeaveType(type); row.setCategory(category);
        }
        row.setYearlyDays(yearlyDays); row.setUpdatedAt(Instant.now());
        leaveBalances.save(row);
    }

    private void directManager(String staffId, String managerId) {
        var row = profiles.findOneBySchoolIdAndStaffKindAndStaffId(SCHOOL, "USER", staffId)
                .orElseGet(Entities.StaffAttendanceProfileEntity::new);
        if (row.getId() == null) row.setId("f34-profile-" + staffId);
        row.setSchoolId(SCHOOL); row.setStaffKind("USER"); row.setStaffId(staffId);
        row.setDirectManagerId(managerId); row.setUpdatedAt(Instant.now());
        profiles.save(row);
    }

    private void teacher(String id, String schoolId, String curriculum) {
        user(id, schoolId, "TEACHER");
        var t = teachers.findById(id).orElseGet(quest.server.auth.Entities.TeacherEntity::new);
        t.setUserId(id); t.setSubjectsJson("[\"math\"]"); t.setCurriculum(curriculum); t.setGradesJson("[1]");
        t.setUpdatedAt(Instant.now());
        teachers.save(t);
    }

    private void user(String id, String schoolId, String role) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@f34.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(id);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }
}
