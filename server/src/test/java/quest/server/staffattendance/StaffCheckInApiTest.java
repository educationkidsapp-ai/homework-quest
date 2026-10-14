package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.UserRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;
import quest.server.workers.WorkerRepository;

/**
 * Feature 15, end to end: the geofence, the approved phone, the server's clock and the employee's own record.
 *
 * <p>{@link #SCHOOL} sits in Asia/Riyadh with its pin at {@link #LAT}/{@link #LON}, a 200 m radius and ten minutes of
 * grace, which is what makes the two interesting fixtures exact: a point 199 m north is inside by a metre and one
 * 201 m north is outside by a metre ({@link GeoTest#northOf}), and 04:12Z is 07:12 in Riyadh, twelve minutes after a
 * 07:00 start. {@link #OTHER} proves isolation, {@link #OFF} that `staffAttendance` hides the routes and
 * {@link #NO_APP} that `staffMobileApp` does too — both switches, not just the feature's own.
 *
 * <p><strong>One employee per scenario.</strong> The record table is unique on (school, employee, day) and the first
 * successful check-in binds a phone, so tests that shared a teacher would be reading each other's state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class StaffCheckInApiTest extends ApiTestSupport {
    private static final String SCHOOL = "ci15-school", OTHER = "ci15-other", OFF = "ci15-off", NO_APP = "ci15-noapp";
    private static final String EDGE = "ci15-edge", ACCURACY = "ci15-accuracy", MOCKER = "ci15-mocker",
            PHONES = "ci15-phones", CLOCKS = "ci15-clocks", TWICE = "ci15-twice", LEAVER = "ci15-leaver",
            NIGHT = "ci15-night", PRIVATE = "ci15-private", RECORD = "ci15-record", MANAGER = "ci15-manager",
            WORKER_ACCOUNT = "ci15-worker-user", OTHER_TEACHER = "ci15-other-teacher", OFF_TEACHER = "ci15-off-teacher",
            NO_APP_TEACHER = "ci15-noapp-teacher", NO_PIN_TEACHER = "ci15-nopin-teacher";
    private static final String WORKER = "ci15-worker";
    /** The school pin: the QA school's, in Riyadh. The only coordinates in the feature, and they live here. */
    private static final double LAT = 24.7136, LON = 46.6753;
    private static final int RADIUS = 200, GRACE = 10;
    /** 07:12 in Riyadh on Sunday 18 October 2026: a working day, twelve minutes after the 07:00 start. */
    private static final Instant LATE_MORNING = Instant.parse("2026-10-18T04:12:00Z");
    private static final Instant EARLY_MORNING = Instant.parse("2026-10-18T03:55:00Z");
    private static final Instant AFTERNOON = Instant.parse("2026-10-18T11:30:00Z");
    private static final Instant EVENING = Instant.parse("2026-10-18T14:45:00Z");
    /** 21:30Z is still the 18th in UTC and already 00:30 on the 19th in Riyadh. */
    private static final Instant PAST_MIDNIGHT_IN_RIYADH = Instant.parse("2026-10-18T21:30:00Z");

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired WorkerRepository workers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AttendanceSettingsRepository settings;
    @Autowired StaffCheckInAttemptRepository attempts;
    @Autowired StaffAttendanceDeviceRepository devices;
    @Autowired AdminJwtService jwt;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll void fixture() {
        school(SCHOOL); school(OTHER); school(OFF); school(NO_APP);
        flags(SCHOOL, true, true); flags(OTHER, true, true); flags(OFF, false, true); flags(NO_APP, true, false);
        pin(SCHOOL); pin(OTHER);
        for (String id : List.of(EDGE, ACCURACY, MOCKER, PHONES, CLOCKS, TWICE, LEAVER, NIGHT, PRIVATE, RECORD))
            user(id, SCHOOL, "TEACHER");
        user(MANAGER, SCHOOL, "MANAGERIAL");
        user(WORKER_ACCOUNT, SCHOOL, "WORKER");
        worker(WORKER, SCHOOL, WORKER_ACCOUNT);
        user(OTHER_TEACHER, OTHER, "TEACHER");
        user(NO_PIN_TEACHER, OTHER, "TEACHER");
        user(OFF_TEACHER, OFF, "TEACHER");
        user(NO_APP_TEACHER, NO_APP, "TEACHER");
    }

    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- the geofence

    @Test void the_edge_of_the_radius_lets_one_metre_inside_through_and_keeps_one_metre_outside_out() throws Exception {
        clock.pinTo(LATE_MORNING);
        String token = token(EDGE, "TEACHER", SCHOOL);

        var outside = checkIn(token, GeoTest.northOf(LAT, RADIUS + 1), LON, 10, false, "edge-phone", null);
        assertThat(outside.get("result").asText()).isEqualTo("REFUSED");
        assertThat(outside.get("reason").asText()).isEqualTo("OUTSIDE");
        assertThat(outside.get("distanceMeters").asInt()).isEqualTo(RADIUS + 1);
        assertThat(outside.has("serverTime")).as("nothing was recorded, so there is no time to show").isFalse();

        var inside = checkIn(token, GeoTest.northOf(LAT, RADIUS - 1), LON, 10, false, "edge-phone", null);
        assertThat(inside.get("result").asText()).isEqualTo("RECORDED");
        assertThat(inside.get("distanceMeters").asInt()).isEqualTo(RADIUS - 1);
        assertThat(inside.get("serverTime").asText()).isEqualTo("07:12");
        assertThat(inside.get("lateMinutes").asInt()).as("12 minutes after a 07:00 start, past 10 of grace").isEqualTo(12);

        // Refused and recorded alike, both attempts are in the log.
        var rows = attempts.findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(SCHOOL, "USER", EDGE);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getResult()).isEqualTo("REFUSED");
        assertThat(rows.get(0).getReason()).isEqualTo("OUTSIDE");
        assertThat(rows.get(1).getResult()).isEqualTo("RECORDED");
    }

    @Test void a_fix_accurate_to_a_hundred_metres_counts_and_one_metre_worse_does_not() throws Exception {
        clock.pinTo(EARLY_MORNING);
        String token = token(ACCURACY, "TEACHER", SCHOOL);

        var weak = checkIn(token, LAT, LON, 101, false, "accuracy-phone", null);
        assertThat(weak.get("result").asText()).isEqualTo("REFUSED");
        assertThat(weak.get("reason").asText()).isEqualTo("WEAK_ACCURACY");
        assertThat(weak.has("distanceMeters")).as("the distance is not computed from a fix we do not trust").isFalse();

        var fine = checkIn(token, LAT, LON, 100, false, "accuracy-phone", null);
        assertThat(fine.get("result").asText()).isEqualTo("RECORDED");
        assertThat(fine.get("serverTime").asText()).isEqualTo("06:55");
        assertThat(fine.get("lateMinutes").asInt()).as("five minutes before the start").isZero();
    }

    @Test void a_mocked_fix_is_refused_and_logged_as_suspicious() throws Exception {
        clock.pinTo(LATE_MORNING);
        var mocked = checkIn(token(MOCKER, "TEACHER", SCHOOL), LAT, LON, 5, true, "mock-phone", null);
        assertThat(mocked.get("result").asText()).isEqualTo("REFUSED");
        assertThat(mocked.get("reason").asText()).isEqualTo("MOCK_LOCATION");

        var rows = attempts.findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(SCHOOL, "USER", MOCKER);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().isMocked()).isTrue();
        assertThat(rows.getFirst().getReason()).isEqualTo("MOCK_LOCATION");
        assertThat(rows.getFirst().getAccuracyM()).isEqualTo(5.0);
        assertThat(rows.getFirst().getInstallId()).isEqualTo("mock-phone");
        // Nothing was recorded, and no phone was bound by a refusal.
        assertThat(devices.findOneBySchoolIdAndStaffKindAndStaffIdAndResetAtIsNull(SCHOOL, "USER", MOCKER)).isEmpty();
    }

    // ---------------------------------------------------------------- the approved phone

    @Test void the_first_phone_is_bound_the_second_is_refused_and_an_admin_reset_lets_a_new_one_in() throws Exception {
        clock.pinTo(LATE_MORNING);
        String token = token(PHONES, "TEACHER", SCHOOL), admin = adminToken();

        assertThat(checkIn(token, LAT, LON, 8, false, "first-phone", null).get("result").asText()).isEqualTo("RECORDED");
        var bound = ok(scoped(get("/admin/staff/USER/" + PHONES + "/attendance-device"), admin, SCHOOL));
        assertThat(bound.get("installId").asText()).isEqualTo("first-phone");
        assertThat(Instant.parse(bound.get("firstSeen").asText())).isEqualTo(LATE_MORNING);

        var stranger = checkIn(token, LAT, LON, 8, false, "second-phone", null);
        assertThat(stranger.get("result").asText()).isEqualTo("REFUSED");
        assertThat(stranger.get("reason").asText()).isEqualTo("DEVICE_NOT_APPROVED");

        // A manager may see which phone is approved; resetting it is the Admin's alone.
        var managerView = ok(bearer(get("/admin/staff/USER/" + PHONES + "/attendance-device"), token(MANAGER, "MANAGERIAL", SCHOOL)));
        assertThat(managerView.get("installId").asText()).isEqualTo("first-phone");
        mvc.perform(bearer(delete("/admin/staff/USER/" + PHONES + "/attendance-device"), token(MANAGER, "MANAGERIAL", SCHOOL)))
                .andExpect(status().isForbidden());

        // The codec omits a null, so "no approved phone" is an empty object rather than an explicit null.
        var afterReset = ok(scoped(delete("/admin/staff/USER/" + PHONES + "/attendance-device"), admin, SCHOOL));
        assertThat(afterReset.has("installId")).as("no phone is approved until she checks in again").isFalse();
        assertThat(ok(scoped(get("/admin/staff/USER/" + PHONES + "/attendance-device"), admin, SCHOOL)).has("installId")).isFalse();

        // The next day on the new phone binds it, and the reset row stays behind it as history.
        clock.pinTo(LATE_MORNING.plusSeconds(86_400));
        assertThat(checkIn(token, LAT, LON, 8, false, "second-phone", null).get("result").asText()).isEqualTo("RECORDED");
        assertThat(ok(scoped(get("/admin/staff/USER/" + PHONES + "/attendance-device"), admin, SCHOOL)).get("installId").asText())
                .isEqualTo("second-phone");
        assertThat(devices.findBySchoolIdAndStaffKindAndStaffIdOrderByFirstSeenAtAsc(SCHOOL, "USER", PHONES)).hasSize(2);
    }

    @Test void an_unknown_employee_has_no_device_page_at_all() throws Exception {
        String admin = adminToken();
        mvc.perform(scoped(get("/admin/staff/USER/ci15-nobody/attendance-device"), admin, SCHOOL)).andExpect(status().isNotFound());
        mvc.perform(scoped(get("/admin/staff/GHOST/" + EDGE + "/attendance-device"), admin, SCHOOL)).andExpect(status().isNotFound());
        mvc.perform(scoped(delete("/admin/staff/GHOST/" + EDGE + "/attendance-device"), admin, SCHOOL)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- the server owns the clock

    @Test void the_recorded_time_is_the_servers_however_wrong_the_phones_is() throws Exception {
        clock.pinTo(LATE_MORNING);
        var result = checkIn(token(CLOCKS, "TEACHER", SCHOOL), LAT, LON, 9, false, "clock-phone", "2026-10-18T01:12:00Z");
        assertThat(result.get("serverTime").asText()).as("07:12 in Riyadh, not the 04:12 the phone claimed").isEqualTo("07:12");
        assertThat(result.get("lateMinutes").asInt()).isEqualTo(12);

        var row = attempts.findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(SCHOOL, "USER", CLOCKS).getFirst();
        assertThat(row.getServerAt()).isEqualTo(LATE_MORNING);
        assertThat(row.getPhoneAt()).as("the phone's claim is kept, for audit and for nothing else").isEqualTo("2026-10-18T01:12:00Z");
    }

    @Test void the_school_day_turns_over_in_the_schools_own_time_zone() throws Exception {
        clock.pinTo(PAST_MIDNIGHT_IN_RIYADH);
        String token = token(NIGHT, "TEACHER", SCHOOL);
        var result = checkIn(token, LAT, LON, 7, false, "night-phone", null);
        assertThat(result.get("result").asText()).isEqualTo("RECORDED");
        assertThat(result.get("serverTime").asText()).isEqualTo("00:30");

        var today = ok(bearer(get("/staff/attendance/today"), token));
        assertThat(today.get("date").asText()).as("21:30Z on the 18th is already the 19th in Riyadh").isEqualTo("2026-10-19");
        assertThat(today.get("checkIn").asText()).isEqualTo("00:30");
        assertThat(jdbc.queryForObject("SELECT CAST(work_day AS VARCHAR) FROM staff_check_ins WHERE staff_id = ?", String.class, NIGHT))
                .isEqualTo("2026-10-19");
    }

    // ---------------------------------------------------------------- the rules

    @Test void a_second_check_in_changes_nothing_and_answers_with_the_first_time() throws Exception {
        clock.pinTo(LATE_MORNING);
        String token = token(TWICE, "TEACHER", SCHOOL);
        assertThat(checkIn(token, LAT, LON, 6, false, "twice-phone", null).get("serverTime").asText()).isEqualTo("07:12");

        clock.pinTo(AFTERNOON);
        var again = checkIn(token, LAT, LON, 6, false, "twice-phone", null);
        assertThat(again.get("result").asText()).isEqualTo("ALREADY");
        assertThat(again.get("serverTime").asText()).as("the time of the first tap, not of this one").isEqualTo("07:12");
        assertThat(ok(bearer(get("/staff/attendance/today"), token)).get("checkIn").asText()).isEqualTo("07:12");
    }

    @Test void a_check_out_needs_a_check_in_and_a_later_one_replaces_the_earlier() throws Exception {
        clock.pinTo(EARLY_MORNING);
        String token = token(LEAVER, "TEACHER", SCHOOL);

        var tooSoon = checkOut(token, LAT, LON, 6, false, "leaver-phone", null);
        assertThat(tooSoon.get("result").asText()).isEqualTo("REFUSED");
        assertThat(tooSoon.get("reason").asText()).isEqualTo("NOT_CHECKED_IN");

        assertThat(checkIn(token, LAT, LON, 6, false, "leaver-phone", null).get("result").asText()).isEqualTo("RECORDED");
        clock.pinTo(AFTERNOON);
        assertThat(checkOut(token, LAT, LON, 6, false, "leaver-phone", null).get("serverTime").asText()).isEqualTo("14:30");
        clock.pinTo(EVENING);
        assertThat(checkOut(token, LAT, LON, 6, false, "leaver-phone", null).get("serverTime").asText()).isEqualTo("17:45");

        var today = ok(bearer(get("/staff/attendance/today"), token));
        assertThat(today.get("checkIn").asText()).isEqualTo("06:55");
        assertThat(today.get("checkOut").asText()).as("the later check-out replaced the earlier").isEqualTo("17:45");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_check_ins WHERE staff_id = ?", Integer.class, LEAVER)).isEqualTo(1);
        assertThat(attempts.findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(SCHOOL, "USER", LEAVER))
                .as("every tap is logged, replaced or not").hasSize(4);

        // A check-out is held to the same geofence as a check-in.
        var fromTheRoad = checkOut(token, GeoTest.northOf(LAT, RADIUS + 1), LON, 6, false, "leaver-phone", null);
        assertThat(fromTheRoad.get("reason").asText()).isEqualTo("OUTSIDE");
        assertThat(ok(bearer(get("/staff/attendance/today"), token)).get("checkOut").asText()).isEqualTo("17:45");
    }

    /** A worker signs in with a WORKER account and is recorded as her `workers` row, not as that account. */
    @Test void my_record_lists_every_day_of_the_month_with_its_status() throws Exception {
        clock.pinTo(LATE_MORNING);
        String token = token(WORKER_ACCOUNT, "WORKER", SCHOOL);
        assertThat(checkIn(token, LAT, LON, 6, false, "worker-phone", null).get("result").asText()).isEqualTo("RECORDED");
        assertThat(devices.findOneBySchoolIdAndStaffKindAndStaffIdAndResetAtIsNull(SCHOOL, "WORKER", WORKER))
                .as("keyed by workers.id, never by the users row behind it").isPresent();

        var month = ok(bearer(get("/staff/attendance/month?month=2026-10"), token));
        assertThat(month.get("month").asText()).isEqualTo("2026-10");
        assertThat(month.get("days")).hasSize(31);
        assertThat(day(month, "2026-10-18").get("status").asText()).isEqualTo("LATE");
        assertThat(day(month, "2026-10-18").get("lateMinutes").asInt()).isEqualTo(12);
        assertThat(day(month, "2026-10-18").get("mode").asText()).isEqualTo("onsite");
        assertThat(day(month, "2026-10-16").get("status").asText()).as("a Friday").isEqualTo("DAY_OFF");
        assertThat(day(month, "2026-10-15").get("status").asText()).as("a past Thursday with no record").isEqualTo("ABSENT");
        assertThat(day(month, "2026-10-19").get("status").asText()).as("tomorrow is not yet an absence").isEqualTo("UPCOMING");

        // No `month` is this month, and a broken one is refused.
        assertThat(ok(bearer(get("/staff/attendance/month"), token)).get("month").asText()).isEqualTo("2026-10");
        mvc.perform(bearer(get("/staff/attendance/month?month=October"), token)).andExpect(status().isBadRequest());
        // The Admin's device page finds her by her worker id.
        assertThat(ok(scoped(get("/admin/staff/WORKER/" + WORKER + "/attendance-device"), adminToken(), SCHOOL)).get("installId").asText())
                .isEqualTo("worker-phone");
    }

    // ---------------------------------------------------------------- privacy

    @Test void no_coordinate_reaches_a_column_or_a_log_line(CapturedOutput output) throws Exception {
        clock.pinTo(LATE_MORNING);
        String token = token(PRIVATE, "TEACHER", SCHOOL);
        double lat = GeoTest.northOf(LAT, 50);
        assertThat(checkIn(token, lat, LON, 6, false, "private-phone", null).get("result").asText()).isEqualTo("RECORDED");
        clock.pinTo(AFTERNOON);
        assertThat(checkOut(token, lat, LON, 6, false, "private-phone", null).get("result").asText()).isEqualTo("RECORDED");
        // A refusal is logged too, and a refused fix is no less private than an accepted one.
        checkIn(token, GeoTest.northOf(LAT, 900), LON, 6, false, "private-phone", null);

        var needles = List.of(String.valueOf(lat), String.valueOf(LON), "24.71", "46.67", "latitude", "longitude");
        for (String table : List.of("staff_check_ins", "staff_check_in_attempts", "staff_attendance_devices")) {
            for (var row : jdbc.queryForList("SELECT * FROM " + table)) {
                for (var cell : row.entrySet()) {
                    String column = cell.getKey().toLowerCase(Locale.ROOT);
                    assertThat(column).as("%s has a coordinate column", table)
                            .doesNotContain("lat").doesNotContain("lon").doesNotContain("lng");
                    String value = String.valueOf(cell.getValue());
                    for (String needle : needles)
                        assertThat(value).as("%s.%s holds `%s`", table, column, needle).doesNotContain(needle);
                }
            }
        }
        // Only the log lines: MockMvc echoes the request body it just sent, which is the test harness talking to
        // itself and not something the server wrote. A log line is one the Logback pattern stamped with an instant.
        String logged = output.getAll().lines().filter(line -> line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logged).as("the context logs something, or this assertion proves nothing").isNotEmpty();
        for (String needle : needles.subList(0, 4))
            assertThat(logged).as("a coordinate reached a log line").doesNotContain(needle);
    }

    // ---------------------------------------------------------------- who may call, and about whom

    @Test void a_parent_cannot_reach_the_staff_attendance_routes() throws Exception {
        for (var request : List.of(get("/staff/attendance/today"), get("/staff/attendance/month"),
                post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON).content(body(LAT, LON, 5, false, "parent-phone", null)))) {
            int status = mvc.perform(request.header("Authorization", PARENT)).andReturn().getResponse().getStatus();
            assertThat(status).isIn(401, 403);
        }
    }

    @Test void with_either_switch_off_the_routes_do_not_exist() throws Exception {
        // `staffAttendance` off: the interceptor hides the whole controller.
        mvc.perform(bearer(get("/staff/attendance/today"), token(OFF_TEACHER, "TEACHER", OFF))).andExpect(status().isNotFound());
        mvc.perform(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content(body(LAT, LON, 5, false, "off-phone", null)), token(OFF_TEACHER, "TEACHER", OFF))).andExpect(status().isNotFound());
        // `staffMobileApp` off: there is no staff app to check in from, and the service answers the same 404.
        mvc.perform(bearer(get("/staff/attendance/today"), token(NO_APP_TEACHER, "TEACHER", NO_APP))).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/staff/attendance/month"), token(NO_APP_TEACHER, "TEACHER", NO_APP))).andExpect(status().isNotFound());
        mvc.perform(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content(body(LAT, LON, 5, false, "noapp-phone", null)), token(NO_APP_TEACHER, "TEACHER", NO_APP))).andExpect(status().isNotFound());
    }

    @Test void one_schools_attempts_devices_and_records_are_invisible_from_another() throws Exception {
        clock.pinTo(LATE_MORNING);
        String admin = adminToken(), otherToken = token(OTHER_TEACHER, "TEACHER", OTHER);
        assertThat(checkIn(otherToken, LAT, LON, 6, false, "other-phone", null).get("result").asText()).isEqualTo("RECORDED");

        // An Admin acting in SCHOOL cannot read or reset the other school's employee.
        mvc.perform(scoped(get("/admin/staff/USER/" + OTHER_TEACHER + "/attendance-device"), admin, SCHOOL)).andExpect(status().isNotFound());
        mvc.perform(scoped(delete("/admin/staff/USER/" + OTHER_TEACHER + "/attendance-device"), admin, SCHOOL)).andExpect(status().isNotFound());
        assertThat(ok(scoped(get("/admin/staff/USER/" + OTHER_TEACHER + "/attendance-device"), admin, OTHER)).get("installId").asText())
                .as("her binding survived the other school's attempt to reset it").isEqualTo("other-phone");

        // Her attempts and her record belong to her school and are not reachable under another's.
        assertThat(attempts.findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(SCHOOL, "USER", OTHER_TEACHER)).isEmpty();
        assertThat(attempts.findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(OTHER, "USER", OTHER_TEACHER)).hasSize(1);

        // A token of one school pointed at another is refused before anything is read.
        mvc.perform(bearer(get("/staff/attendance/today"), otherToken).header(TenantContext.HEADER, SCHOOL)).andExpect(status().isForbidden());
    }

    @Test void an_employee_of_no_school_and_a_body_without_a_phone_are_refused_outright() throws Exception {
        clock.pinTo(LATE_MORNING);
        // The platform Admin keeps no attendance of her own: she is nobody's employee.
        mvc.perform(scoped(get("/staff/attendance/today"), adminToken(), SCHOOL)).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content(body(LAT, LON, 5, false, "  ", null)), token(RECORD, "TEACHER", SCHOOL))).andExpect(status().isBadRequest());
        mvc.perform(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":91.0,\"longitude\":46.6,\"accuracyMeters\":5.0,\"installId\":\"p\"}"),
                token(RECORD, "TEACHER", SCHOOL))).andExpect(status().isBadRequest());
        mvc.perform(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON).content("{}"),
                token(RECORD, "TEACHER", SCHOOL))).andExpect(status().isBadRequest());
    }

    @Test void a_school_that_has_not_placed_its_pin_refuses_every_attempt() throws Exception {
        clock.pinTo(LATE_MORNING);
        settings.findOneBySchoolId(OTHER).ifPresent(row -> { row.setLatitude(null); row.setLongitude(null); settings.save(row); });
        try {
            var result = checkIn(token(NO_PIN_TEACHER, "TEACHER", OTHER), LAT, LON, 6, false, "nopin-phone", null);
            assertThat(result.get("result").asText()).isEqualTo("REFUSED");
            assertThat(result.get("reason").asText()).isEqualTo("NO_GEOFENCE");
        } finally { pin(OTHER); }
    }

    // ---------------------------------------------------------------- fixture and helpers

    private JsonNode checkIn(String token, double lat, double lon, double accuracy, boolean mocked, String installId, String phoneTime) throws Exception {
        return ok(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content(body(lat, lon, accuracy, mocked, installId, phoneTime)), token));
    }

    private JsonNode checkOut(String token, double lat, double lon, double accuracy, boolean mocked, String installId, String phoneTime) throws Exception {
        return ok(bearer(post("/staff/attendance/check-out").contentType(MediaType.APPLICATION_JSON)
                .content(body(lat, lon, accuracy, mocked, installId, phoneTime)), token));
    }

    private static String body(double lat, double lon, double accuracy, boolean mocked, String installId, String phoneTime) {
        return "{\"latitude\":" + lat + ",\"longitude\":" + lon + ",\"accuracyMeters\":" + accuracy
                + ",\"mocked\":" + mocked + ",\"installId\":\"" + installId + "\""
                + (phoneTime == null ? "" : ",\"phoneTime\":\"" + phoneTime + "\"") + "}";
    }

    private static JsonNode day(JsonNode month, String date) {
        for (var row : month.get("days")) if (date.equals(row.get("date").asText())) return row;
        throw new AssertionError(date + " is not in the month");
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

    private String token(String userId, String role, String schoolId) { return jwt.issue(userId, userId + "@ci15.test", role, schoolId).token(); }

    private void school(String id) {
        var s = schools.findById(id).orElseGet(SchoolEntity::new);
        s.setId(id); s.setName(id); s.setCode(id.replace("-", "").toUpperCase(Locale.ROOT));
        s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1,2,3]");
        s.setStatus("active"); s.setTimezone("Asia/Riyadh");
        if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private void flags(String schoolId, boolean attendance, boolean staffApp) {
        flag(schoolId, FlagKeys.STAFF_ATTENDANCE, attendance);
        flag(schoolId, FlagKeys.STAFF_MOBILE_APP, staffApp);
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

    /** The pin, the radius and the grace period — feature 14's page, written straight so the fixture is one call. */
    private void pin(String schoolId) {
        var row = settings.findOneBySchoolId(schoolId).orElseGet(Entities.AttendanceSettingsEntity::new);
        row.setSchoolId(schoolId); row.setLatitude(LAT); row.setLongitude(LON);
        row.setRadiusMeters(RADIUS); row.setGraceMinutes(GRACE);
        row.setNotArrivedMinutes(60); row.setMissingCheckOutMinutes(60);
        row.setDirectorNotify("DIGEST"); row.setAdminNotify("DIGEST"); row.setDigestTime(LocalTime.of(7, 30));
        row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        settings.save(row);
    }

    private void user(String id, String schoolId, String role) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@ci15.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(id);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    private void worker(String id, String schoolId, String userId) {
        var w = workers.findById(id).orElseGet(quest.server.workers.Entities.WorkerEntity::new);
        w.setId(id); w.setSchoolId(schoolId); w.setFullName("Walid Worker"); w.setJob("caretaker");
        w.setActive(true); w.setUserId(userId);
        if (w.getCreatedAt() == null) w.setCreatedAt(Instant.now());
        workers.save(w);
    }
}
