package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
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
 * Feature 18, end to end: an employee's <strong>own</strong> remote day skips the geofence once her manager has
 * approved it, and a correction reaches the record only through the direct manager's or the Admin's approval — with
 * its audit, the originals kept, and the day's statuses recomputed.
 *
 * <p>The Admin's school-wide remote day moved to feature 23 ({@link RemoteStudyDaysApiTest}) and `remoteStudyDays` is
 * deliberately <strong>off</strong> here, which is how this file now also proves that the personal path owes nothing
 * to feature 23's flag.
 *
 * <p>{@link #SCHOOL} is in Asia/Riyadh, teaches two academies (`british`, `american`) and has its pin at
 * {@link #LAT}/{@link #LON} with a 200 m radius. {@link #FAR} is a degree north — about 111 km, "from home". The
 * default hours are 07:00–14:00, Sunday to Thursday. Every scenario has its own employee and its own date, because a
 * remote day applies to the whole school for that date and the record table is unique on (employee, day).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteDaysAndCorrectionsApiTest extends ApiTestSupport {
    private static final String SCHOOL = "rd18-school", OTHER = "rd18-other";
    private static final String HOME = "rd18-home", BRITISH = "rd18-british",
            ASKER = "rd18-asker", LATE = "rd18-late", ABSENT = "rd18-absent", REFUSED = "rd18-refused",
            DIRECT = "rd18-direct", MANAGER = "rd18-manager", OTHER_MANAGER = "rd18-other-manager",
            ADMIN = "rd18-admin", WORKER_ACCOUNT = "rd18-worker-user", OTHER_TEACHER = "rd18-other-teacher";
    private static final String WORKER = "rd18-worker";
    private static final double LAT = 24.7136, LON = 46.6753, FAR = LAT + 1.0;
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired WorkerRepository workers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AttendanceSettingsRepository settings;
    @Autowired StaffAttendanceProfileRepository profiles;
    @Autowired StaffCheckInRepository records;
    @Autowired StaffCheckInAttemptRepository attempts;
    @Autowired StaffAttendanceDayRepository days;
    @Autowired StaffAttendanceRequestRepository requests;
    @Autowired AdminJwtService jwt;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll void fixture() {
        school(SCHOOL); school(OTHER);
        flags(SCHOOL); flags(OTHER);
        pin(SCHOOL); pin(OTHER);
        for (String id : List.of(HOME, BRITISH, ASKER, LATE, ABSENT, REFUSED, DIRECT)) teacher(id, SCHOOL, "british");
        user(MANAGER, SCHOOL, "MANAGERIAL");
        user(OTHER_MANAGER, SCHOOL, "MANAGERIAL");
        user(ADMIN, null, "ADMIN");
        user(WORKER_ACCOUNT, SCHOOL, "WORKER");
        worker(WORKER, SCHOOL, WORKER_ACCOUNT);
        teacher(OTHER_TEACHER, OTHER, "british");
        for (String id : List.of(ASKER, LATE, ABSENT, REFUSED, DIRECT)) directManager(id, MANAGER);
    }

    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- a personal remote day, approved by the manager

    @Test void an_approved_remote_day_lets_her_check_in_from_home_and_only_then() throws Exception {
        clock.pinTo(at("2026-10-20", "16:00"));
        var request = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"REMOTE_DAY\",\"date\":\"2026-10-21\",\"reason\":\"Plumber at home\"}"), token(ASKER, "TEACHER")));
        String id = request.get("id").asText();
        assertThat(request.get("status").asText()).isEqualTo("PENDING");
        assertThat(notifications(MANAGER, "attendance.request", "request=" + id)).as("her direct manager is told, with the id").isEqualTo(1);
        assertThat(notifications(OTHER_MANAGER, "attendance.request", "request=" + id)).isZero();

        clock.pinTo(at("2026-10-21", "06:55"));
        var pending = checkIn(token(ASKER, "TEACHER"), FAR, "asker-phone");
        assertThat(pending.get("reason").asText()).as("a pending request is not a remote day").isEqualTo("OUTSIDE");

        var approved = ok(bearer(post("/attendance/requests/" + id + "/approve"), token(MANAGER, "MANAGERIAL")));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("decidedBy").asText()).isEqualTo(MANAGER);
        assertThat(approved.get("decidedAt").asLong()).isEqualTo(at("2026-10-21", "06:55").toEpochMilli());

        assertThat(checkIn(token(ASKER, "TEACHER"), FAR, "asker-phone").get("result").asText()).isEqualTo("RECORDED");
        assertThat(record(ASKER, "2026-10-21").getMode()).isEqualTo("remote");
    }

    // ---------------------------------------------------------------- who may decide

    @Test void only_the_direct_manager_or_the_admin_decides() throws Exception {
        clock.pinTo(at("2026-10-22", "09:00"));
        String id = correction(DIRECT, "2026-10-15", "FORGOT_CHECK_IN", "07:00", "14:00", null).get("id").asText();

        forbidden(post("/attendance/requests/" + id + "/approve"), token(OTHER_MANAGER, "MANAGERIAL"));
        forbidden(post("/attendance/requests/" + id + "/approve"), token(DIRECT, "TEACHER"));      // never herself
        forbidden(post("/attendance/requests/" + id + "/reject"), token(BRITISH, "TEACHER"));
        mvc.perform(post("/attendance/requests/" + id + "/approve").header("Authorization", PARENT)).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/attendance/requests/" + id + "/approve"), token(WORKER_ACCOUNT, "WORKER"))).andExpect(status().isForbidden());

        assertThat(ids(ok(bearer(get("/attendance/requests?status=PENDING"), token(OTHER_MANAGER, "MANAGERIAL"))))).doesNotContain(id);
        assertThat(ids(ok(bearer(get("/attendance/requests?status=PENDING"), token(MANAGER, "MANAGERIAL"))))).contains(id);
        assertThat(ids(ok(scoped(get("/attendance/requests?status=PENDING"), admin(), SCHOOL)))).contains(id);

        ok(scoped(post("/attendance/requests/" + id + "/approve"), admin(), SCHOOL));
        mvc.perform(bearer(post("/attendance/requests/" + id + "/reject"), token(MANAGER, "MANAGERIAL")))
                .andExpect(status().isConflict());
    }

    @Test void with_no_manager_to_fall_back_on_only_the_admin_decides_a_worker_s_request() throws Exception {
        clock.pinTo(at("2026-10-22", "09:30"));
        String id = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"REMOTE_DAY\",\"date\":\"2026-10-26\",\"reason\":\"Errand\"}"), token(WORKER_ACCOUNT, "WORKER"))).get("id").asText();
        forbidden(post("/attendance/requests/" + id + "/approve"), token(MANAGER, "MANAGERIAL"));
        assertThat(ok(scoped(post("/attendance/requests/" + id + "/approve"), admin(), SCHOOL)).get("status").asText()).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- corrections

    @Test void an_approved_correction_rewrites_the_record_keeps_the_originals_and_recomputes_the_day() throws Exception {
        clock.pinTo(at("2026-10-22", "07:12"));                                 // twelve minutes late, on site
        var late = checkIn(token(LATE, "TEACHER"), LAT, "late-phone");
        assertThat(late.get("lateMinutes").asInt()).isEqualTo(12);

        String photo = upload(token(LATE, "TEACHER"));
        var request = correction(LATE, "2026-10-22", "PHONE_PROBLEM", "06:58", null, photo);
        String id = request.get("id").asText();
        assertThat(request.get("attachment").get("id").asText()).isEqualTo(photo);

        // The photo is read by whoever decides, and by nobody else in the school.
        mvc.perform(bearer(get("/media/attachments/" + photo), token(MANAGER, "MANAGERIAL"))).andExpect(status().isOk());
        mvc.perform(bearer(get("/media/attachments/" + photo), token(OTHER_MANAGER, "MANAGERIAL"))).andExpect(status().isNotFound());

        clock.pinTo(at("2026-10-22", "09:00"));
        var approved = ok(bearer(post("/attendance/requests/" + id + "/approve"), token(MANAGER, "MANAGERIAL")));
        assertThat(approved.get("originalCheckIn").asText()).isEqualTo("07:12");
        assertThat(approved.get("decidedBy").asText()).isEqualTo(MANAGER);

        var row = record(LATE, "2026-10-22");
        assertThat(row.getMode()).isEqualTo("correction");
        assertThat(row.getCheckInAt()).isEqualTo(at("2026-10-22", "06:58"));
        assertThat(row.getOriginalCheckInAt()).isEqualTo(at("2026-10-22", "07:12"));
        assertThat(row.getCorrectedBy()).isEqualTo(MANAGER);
        assertThat(row.getCorrectedAt()).isEqualTo(at("2026-10-22", "09:00"));
        assertThat(row.getCorrectionRequestId()).isEqualTo(id);

        assertThat(attempts.findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(SCHOOL, "USER", LATE))
                .as("the original tap stays in the log").hasSize(1)
                .first().satisfies(a -> assertThat(a.getServerAt()).isEqualTo(at("2026-10-22", "07:12")));
        var status = days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", LATE, LocalDate.parse("2026-10-22")).orElseThrow();
        assertThat(status.getLateMinutes()).as("06:58 is on time").isNull();

        assertThat(link(LATE, "attendance.decision")).contains("decision=approved");
        var month = ok(bearer(get("/staff/attendance/month?month=2026-10"), token(LATE, "TEACHER")));
        assertThat(day(month, "2026-10-22").get("status").asText()).isEqualTo("ON_TIME");
        assertThat(day(month, "2026-10-22").get("mode").asText()).isEqualTo("correction");
    }

    @Test void a_corrected_absence_is_no_longer_absent() throws Exception {
        clock.pinTo(at("2026-10-22", "10:00"));
        String id = correction(ABSENT, "2026-10-15", "FORGOT_CHECK_IN", "06:50", "14:05", null).get("id").asText();
        ok(bearer(post("/attendance/requests/" + id + "/approve"), token(MANAGER, "MANAGERIAL")));

        var row = record(ABSENT, "2026-10-15");
        assertThat(row.getMode()).isEqualTo("correction");
        assertThat(row.getOriginalCheckInAt()).as("there was nothing to replace").isNull();
        var status = days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", ABSENT, LocalDate.parse("2026-10-15")).orElseThrow();
        assertThat(status.isAbsent()).isFalse();
        assertThat(status.isNotArrived()).isFalse();
        assertThat(status.isMissingCheckout()).isFalse();
        assertThat(status.getEarlyLeaveMinutes()).isNull();
    }

    @Test void a_rejected_request_changes_nothing_and_she_is_told() throws Exception {
        clock.pinTo(at("2026-10-22", "11:00"));
        String id = correction(REFUSED, "2026-10-14", "FORGOT_CHECK_IN", "07:00", "14:00", null).get("id").asText();
        var rejected = ok(bearer(post("/attendance/requests/" + id + "/reject"), token(MANAGER, "MANAGERIAL")));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", REFUSED, LocalDate.parse("2026-10-14"))).isEmpty();
        assertThat(link(REFUSED, "attendance.decision")).contains("decision=rejected");

        var mine = ok(bearer(get("/staff/attendance/requests"), token(REFUSED, "TEACHER")));
        assertThat(mine.get("requests").get(0).get("status").asText()).isEqualTo("REJECTED");
    }

    @Test void a_correction_with_no_check_in_on_a_day_without_one_is_refused() throws Exception {
        clock.pinTo(at("2026-10-22", "11:30"));
        mvc.perform(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CORRECTION\",\"date\":\"2026-10-13\",\"reason\":\"x\",\"correctionKind\":\"FORGOT_CHECK_OUT\",\"checkOut\":\"14:00\"}"),
                token(REFUSED, "TEACHER"))).andExpect(status().isBadRequest());
    }

    @Test void the_admin_corrects_a_day_directly_only_with_a_reason() throws Exception {
        clock.pinTo(at("2026-10-22", "12:00"));
        String path = "/admin/staff/USER/" + HOME + "/attendance-corrections";
        mvc.perform(scoped(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-12\",\"reason\":\"  \",\"checkIn\":\"07:00\"}"), admin(), SCHOOL))
                .andExpect(status().isBadRequest());
        mvc.perform(bearer(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-12\",\"reason\":\"Device fault\",\"checkIn\":\"07:00\"}"), token(MANAGER, "MANAGERIAL")))
                .andExpect(status().isForbidden());

        var done = ok(scoped(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-12\",\"reason\":\"Device fault\",\"checkIn\":\"07:00\",\"checkOut\":\"14:00\"}"), admin(), SCHOOL));
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(done.get("decidedBy").asText()).isEqualTo(ADMIN);
        var row = record(HOME, "2026-10-12");
        assertThat(row.getMode()).isEqualTo("correction");
        assertThat(row.getCorrectedBy()).isEqualTo(ADMIN);
        assertThat(requests.countAppliedCorrections(SCHOOL, "USER", HOME, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))).isEqualTo(1);
    }

    // ---------------------------------------------------------------- school isolation

    @Test void another_school_never_sees_or_decides_this_school_s_requests() throws Exception {
        clock.pinTo(at("2026-10-22", "13:00"));
        String id = correction(DIRECT, "2026-10-13", "FORGOT_CHECK_IN", "07:00", "14:00", null).get("id").asText();
        user("rd18-other-manager-b", OTHER, "MANAGERIAL");
        String foreign = token("rd18-other-manager-b", "MANAGERIAL", OTHER);
        mvc.perform(bearer(post("/attendance/requests/" + id + "/approve"), foreign)).andExpect(status().isNotFound());
        assertThat(ids(ok(bearer(get("/attendance/requests"), foreign)))).doesNotContain(id);
        assertThat(ids(ok(scoped(get("/attendance/requests"), admin(), OTHER)))).doesNotContain(id);
        var mine = ok(bearer(get("/staff/attendance/requests"), token(OTHER_TEACHER, "TEACHER", OTHER)));
        assertThat(mine.get("requests")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode correction(String userId, String date, String kind, String in, String out, String attachmentId) throws Exception {
        String body = "{\"type\":\"CORRECTION\",\"date\":\"" + date + "\",\"reason\":\"Forgot\",\"correctionKind\":\"" + kind + "\""
                + (in == null ? "" : ",\"checkIn\":\"" + in + "\"") + (out == null ? "" : ",\"checkOut\":\"" + out + "\"")
                + (attachmentId == null ? "" : ",\"attachmentId\":\"" + attachmentId + "\"") + "}";
        return ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON).content(body), token(userId, "TEACHER")));
    }

    private String upload(String token) throws Exception {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        var file = new MockMultipartFile("file", "receipt.png", "image/png", bytes.toByteArray());
        return ok(bearer(multipart("/staff/attendance/attachments").file(file), token)).get("id").asText();
    }

    private JsonNode checkIn(String token, double lat, String installId) throws Exception {
        return ok(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":" + lat + ",\"longitude\":" + LON + ",\"accuracyMeters\":10,\"mocked\":false,\"installId\":\"" + installId + "\"}"), token));
    }

    private Entities.StaffCheckInEntity record(String userId, String date) {
        return records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", userId, LocalDate.parse(date)).orElseThrow();
    }

    /** Notifications of one kind whose link names {@code date} (empty: any). */
    private int notifications(String userId, String kind, String date) {
        Integer n = jdbc.queryForObject("select count(*) from notifications where user_id = ? and kind = ? and link like ?",
                Integer.class, userId, kind, "%" + date + "%");
        return n == null ? 0 : n;
    }

    private String link(String userId, String kind) {
        return jdbc.queryForObject("select link from notifications where user_id = ? and kind = ? order by created_at desc limit 1",
                String.class, userId, kind);
    }

    private static List<String> ids(JsonNode list) {
        var out = new java.util.ArrayList<String>();
        for (var r : list.get("requests")) out.add(r.get("id").asText());
        return out;
    }

    private static JsonNode day(JsonNode month, String date) {
        for (var row : month.get("days")) if (date.equals(row.get("date").asText())) return row;
        throw new AssertionError(date + " is not in the month");
    }

    private static Instant at(String date, String time) { return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(RIYADH).toInstant(); }

    private void forbidden(MockHttpServletRequestBuilder request, String token) throws Exception {
        mvc.perform(bearer(request, token)).andExpect(status().isForbidden());
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

    private String admin() { return jwt.issue(ADMIN, ADMIN + "@rd18.test", "ADMIN", null).token(); }

    private String token(String userId, String role) { return token(userId, role, SCHOOL); }

    private String token(String userId, String role, String schoolId) { return jwt.issue(userId, userId + "@rd18.test", role, schoolId).token(); }

    private void school(String id) {
        var s = schools.findById(id).orElseGet(SchoolEntity::new);
        s.setId(id); s.setName(id); s.setCode(id.replace("-", "").toUpperCase(Locale.ROOT));
        s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
        s.setStatus("active"); s.setTimezone("Asia/Riyadh");
        if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private void flags(String schoolId) {
        flag(schoolId, FlagKeys.STAFF_ATTENDANCE);
        flag(schoolId, FlagKeys.STAFF_MOBILE_APP);
        featureFlags.invalidate(schoolId);
    }

    private void flag(String schoolId, String key) {
        var row = schoolFlags.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(true); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
    }

    private void pin(String schoolId) {
        var row = settings.findOneBySchoolId(schoolId).orElseGet(Entities.AttendanceSettingsEntity::new);
        row.setSchoolId(schoolId); row.setLatitude(LAT); row.setLongitude(LON);
        row.setRadiusMeters(200); row.setGraceMinutes(10);
        row.setNotArrivedMinutes(60); row.setMissingCheckOutMinutes(60);
        row.setDirectorNotify("DIGEST"); row.setAdminNotify("DIGEST"); row.setDigestTime(LocalTime.of(7, 30));
        row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        settings.save(row);
    }

    private void directManager(String staffId, String managerId) {
        var row = profiles.findOneBySchoolIdAndStaffKindAndStaffId(SCHOOL, "USER", staffId).orElseGet(Entities.StaffAttendanceProfileEntity::new);
        if (row.getId() == null) row.setId("rd18-profile-" + staffId);
        row.setSchoolId(SCHOOL); row.setStaffKind("USER"); row.setStaffId(staffId);
        row.setDirectManagerId(managerId); row.setUpdatedAt(Instant.now());
        profiles.save(row);
    }

    private void teacher(String id, String schoolId, String curriculum) {
        user(id, schoolId, "TEACHER");
        var t = teachers.findById(id).orElseGet(quest.server.auth.Entities.TeacherEntity::new);
        t.setUserId(id); t.setSubjectsJson("[\"math\"]"); t.setCurriculum(curriculum); t.setGradesJson("[1]"); t.setUpdatedAt(Instant.now());
        teachers.save(t);
    }

    private void user(String id, String schoolId, String role) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@rd18.test"); u.setPasswordHash("x");
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
