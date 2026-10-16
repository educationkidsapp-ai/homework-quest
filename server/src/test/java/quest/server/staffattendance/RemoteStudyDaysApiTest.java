package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.ClassFixtures;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.push.ParentDeviceRepository;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TeachingAssignmentRepository;
import quest.server.tenancy.TenantContext;

/**
 * Feature 23, end to end: a remote study day for the school, one academy or chosen grades.
 *
 * <p>{@link #SCHOOL} is in Asia/Riyadh, teaches `british` and `american` in grades 1 and 2, and has its pin at
 * {@link #LAT}/{@link #LON} with a 200 m radius; {@link #FAR} is a degree north — about 111 km, "from home". It has
 * one section per (academy, grade) and one child, with her own parent, in each: the smallest fixture in which
 * "another academy" and "another grade" are different people, which is what the two scopes have to tell apart.
 *
 * <p>The two scopes are the point of the file. <strong>Staff</strong> are in scope by academy alone — a day's grades
 * never narrow who may check in from home — while <strong>children and their parents</strong> are in scope by the
 * academy and the grade. Every scenario uses its own date, because a day applies to everyone in scope for that date.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteStudyDaysApiTest extends ApiTestSupport {
    private static final String SCHOOL = "rsd-school", OTHER = "rsd-other", CODE = "RSDAAA";
    private static final String ADMIN = "rsd-admin", MANAGER = "rsd-manager";
    private static final String BRITISH_1 = "rsd-british-1", BRITISH_2 = "rsd-british-2", AMERICAN_1 = "rsd-american-1";
    private static final String ASKER = "rsd-asker", ASKER_OFF = "rsd-asker-off";
    private static final String SECTION_B1 = "rsd-section-b1", SECTION_B2 = "rsd-section-b2", SECTION_A1 = "rsd-section-a1";
    private static final String PARENT_B1 = "rsd-parent-b1", PARENT_B2 = "rsd-parent-b2", PARENT_A1 = "rsd-parent-a1";
    private static final double LAT = 24.7136, LON = 46.6753, FAR = LAT + 1.0;
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired ChildRepository children;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AttendanceSettingsRepository settings;
    @Autowired StaffCheckInRepository records;
    @Autowired RemoteStudyDayRepository studyDays;
    @Autowired StaffAttendanceJob job;
    @Autowired AdminJwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PushSender pushes;
    @Autowired ParentDeviceRepository parentDevices;

    private String childB1, childB2, childA1;

    @BeforeAll void fixture() throws Exception {
        school(SCHOOL, CODE); school(OTHER, "RSDBBB");
        flags(SCHOOL, true); flags(OTHER, true);
        pin(SCHOOL); pin(OTHER);
        user(ADMIN, null, "ADMIN");
        user(MANAGER, SCHOOL, "MANAGERIAL");
        // BRITISH_2 teaches grade 2 only, which is what makes "grades never narrow staff" provable: a day declared
        // for grade 1 reaches her because she teaches the *academy*, not because her profile happens to hold grade 1.
        teacher(BRITISH_1, "british", "[1,2]"); teacher(BRITISH_2, "british", "[2]"); teacher(AMERICAN_1, "american", "[1,2]");
        teacher(ASKER, "british", "[1,2]"); teacher(ASKER_OFF, "british", "[1,2]");
        ClassFixtures.section(classes, assignments, SECTION_B1, SCHOOL, "british", 1, "math", BRITISH_1);
        ClassFixtures.section(classes, assignments, SECTION_B2, SCHOOL, "british", 2, "math", BRITISH_2);
        ClassFixtures.section(classes, assignments, SECTION_A1, SCHOOL, "american", 1, "math", AMERICAN_1);
        childB1 = child("Lila", SECTION_B1, "british", 1, PARENT_B1);
        childB2 = child("Bilal", SECTION_B2, "british", 2, PARENT_B2);
        childA1 = child("Hana", SECTION_A1, "american", 1, PARENT_A1);
    }

    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- V46 copied feature 18's rows

    /**
     * The copy, run as the migration ships it. Flyway has already applied V46 to this schema, so the statement cannot
     * be observed doing its work on data that arrived before it; instead the test <em>reads the `INSERT … SELECT` out
     * of `V46__remote_study_days.sql`</em>, writes a feature 18 row behind it, and executes that very statement —
     * twice, because the rule is that a migration may be re-run over data it has already seen. What it proves is what
     * a school cares about: a day declared under feature 18 is a remote study day now, visible on the new route,
     * copied once and with no grades.
     */
    @Test void the_migration_copies_feature_18s_remote_days_into_the_new_table() throws Exception {
        String sql = copyStatement();
        assertThat(sql).contains("FROM attendance_remote_days");
        clock.pinTo(at("2027-05-04", "09:00"));
        jdbc.update("insert into attendance_remote_days (id, school_id, work_day, academy, reason, created_by, created_at)"
                + " values (?, ?, ?, ?, ?, ?, ?)", "rsd-legacy", SCHOOL, LocalDate.parse("2027-05-05"), "british",
                "Rain day declared under feature 18", ADMIN, Instant.now(clock));
        try {
            jdbc.execute(sql);
            jdbc.execute(sql);                                                    // idempotent over data it has seen
            var copied = ok(scoped(get("/admin/remote-study-days"), admin(), SCHOOL));
            var day = dayOf(copied, "2027-05-05");
            assertThat(day.get("id").asText()).as("the same row, not a second one").isEqualTo("rsd-legacy");
            assertThat(day.get("academy").asText()).isEqualTo("british");
            assertThat(day.get("grades")).as("feature 18 had no grades").isEmpty();
            assertThat(day.get("reason").asText()).isEqualTo("Rain day declared under feature 18");
        } finally {
            jdbc.update("delete from remote_study_days where id = ?", "rsd-legacy");
            jdbc.update("delete from attendance_remote_days where id = ?", "rsd-legacy");
        }
    }

    // ---------------------------------------------------------------- staff: the academy, never the grades

    @Test void an_academy_s_remote_study_day_lets_its_teachers_check_in_from_home_and_not_the_other_academy_s() throws Exception {
        clock.pinTo(at("2027-05-09", "15:00"));                                   // the day before, in the afternoon
        var created = ok(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2027-05-10\",\"academy\":\"British\",\"grades\":[1],\"reason\":\"Rain in the north\"}"), admin(), SCHOOL));
        assertThat(created.get("academy").asText()).as("stored lower-cased").isEqualTo("british");
        assertThat(created.get("grades").get(0).asInt()).isEqualTo(1);
        assertThat(notices(BRITISH_1, "2027-05-10")).as("tomorrow: announced at once").isEqualTo(1);
        assertThat(notices(BRITISH_2, "2027-05-10")).as("she teaches grade 2 only, and grades never narrow staff").isEqualTo(1);
        assertThat(notices(AMERICAN_1, "2027-05-10")).isZero();

        clock.pinTo(at("2027-05-10", "07:05"));
        assertThat(checkIn(token(BRITISH_1), FAR, "rsd-b1-phone").get("result").asText()).isEqualTo("RECORDED");
        assertThat(record(BRITISH_1, "2027-05-10").getMode()).isEqualTo("remote");
        assertThat(checkIn(token(BRITISH_2), FAR, "rsd-b2-phone").get("result").asText()).isEqualTo("RECORDED");

        var american = checkIn(token(AMERICAN_1), FAR, "rsd-a1-phone");
        assertThat(american.get("result").asText()).isEqualTo("REFUSED");
        assertThat(american.get("reason").asText()).isEqualTo("OUTSIDE");
    }

    @Test void a_later_day_is_announced_the_evening_before_and_only_once() throws Exception {
        clock.pinTo(at("2027-05-11", "10:00"));
        ok(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2027-05-14\",\"reason\":\"Marking day\"}"), admin(), SCHOOL));
        assertThat(notices(BRITISH_1, "2027-05-14")).as("three days ahead: not yet").isZero();

        clock.pinTo(at("2027-05-13", "17:59"));
        job.run(SCHOOL);
        assertThat(notices(BRITISH_1, "2027-05-14")).isZero();

        clock.pinTo(at("2027-05-13", "18:00"));
        job.run(SCHOOL);
        job.run(SCHOOL);
        assertThat(notices(BRITISH_1, "2027-05-14")).isEqualTo(1);
        assertThat(notices(AMERICAN_1, "2027-05-14")).as("no academy named is the whole school").isEqualTo(1);
    }

    /** Feature 18's own path owes nothing to feature 23's flag: it is her request, approved for her, flag or no flag. */
    @Test void a_personal_approved_remote_day_still_works_with_the_flag_on_and_with_it_off() throws Exception {
        clock.pinTo(at("2027-05-16", "16:00"));
        approveOwnRemoteDay(ASKER, "2027-05-17");
        clock.pinTo(at("2027-05-17", "06:55"));
        assertThat(checkIn(token(ASKER), FAR, "rsd-asker-phone").get("result").asText()).isEqualTo("RECORDED");
        assertThat(record(ASKER, "2027-05-17").getMode()).isEqualTo("remote");

        flags(SCHOOL, false);
        try {
            clock.pinTo(at("2027-05-18", "16:00"));
            approveOwnRemoteDay(ASKER_OFF, "2027-05-19");
            clock.pinTo(at("2027-05-19", "06:55"));
            assertThat(checkIn(token(ASKER_OFF), FAR, "rsd-asker-off-phone").get("result").asText()).isEqualTo("RECORDED");
            assertThat(record(ASKER_OFF, "2027-05-19").getMode()).isEqualTo("remote");
        } finally {
            flags(SCHOOL, true);
        }
    }

    // ---------------------------------------------------------------- parents: the academy and the grade

    @Test void the_parents_of_the_classes_in_scope_get_the_announcement_and_the_push_and_nobody_else_does() throws Exception {
        String inScope = PushProbe.token("rsd-b1"), otherGrade = PushProbe.token("rsd-b2"), otherAcademy = PushProbe.token("rsd-a1");
        PushProbe.register(mvc, bearer(PARENT_B1), inScope, "ar");
        PushProbe.register(mvc, bearer(PARENT_B2), otherGrade, null);
        PushProbe.register(mvc, bearer(PARENT_A1), otherAcademy, null);

        clock.pinTo(at("2027-05-20", "08:00"));
        ok(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2027-05-21\",\"academy\":\"british\",\"grades\":[1],\"reason\":\"Storm warning\"}"), admin(), SCHOOL));

        String announcement = announcementOf("2027-05-21");
        var item = item(parentFeed(PARENT_B1, childB1), announcement);
        assertThat(item.get("kind").asText()).isEqualTo("announcement");
        assertThat(item.get("bodyEn").asText()).contains("Remote study day on 2027-05-21", "Storm warning");
        assertThat(item.get("bodyAr").asText()).contains("يوم دراسة عن بعد");
        assertThat(item.get("expiresAt").asLong()).as("gone at the end of that day, in the school's zone")
                .isEqualTo(at("2027-05-22", "00:00").toEpochMilli());
        assertThat(item.get("sectionIds")).containsExactly(mapper.readTree("\"" + SECTION_B1 + "\""));

        assertThat(PushProbe.await(pushes, inScope, 1)).hasSize(1);
        assertThat(ids(parentFeed(PARENT_B2, childB2))).as("another grade of the same academy").doesNotContain(announcement);
        assertThat(ids(parentFeed(PARENT_A1, childA1))).as("another academy").doesNotContain(announcement);
        assertThat(PushProbe.sentTo(pushes, otherGrade)).isEmpty();
        assertThat(PushProbe.sentTo(pushes, otherAcademy)).isEmpty();
    }

    @Test void deleting_a_remote_study_day_expires_its_announcement_and_pushes_nothing_new() throws Exception {
        String phone = PushProbe.token("rsd-cancel");
        PushProbe.register(mvc, bearer(PARENT_B1), phone, null);

        clock.pinTo(at("2027-05-23", "08:00"));
        String id = ok(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2027-05-24\",\"grades\":[1],\"reason\":\"Possible storm\"}"), admin(), SCHOOL)).get("id").asText();
        String announcement = studyDays.findById(id).orElseThrow().getBroadcastId();
        assertThat(ids(parentFeed(PARENT_B1, childB1))).contains(announcement);
        assertThat(PushProbe.await(pushes, phone, 1)).hasSize(1);

        mvc.perform(scoped(delete("/admin/remote-study-days/" + id), admin(), SCHOOL)).andExpect(status().isNoContent());
        assertThat(ids(parentFeed(PARENT_B1, childB1))).as("it left her feed the moment it was called off").doesNotContain(announcement);
        assertThat(dates(ok(scoped(get("/admin/remote-study-days"), admin(), SCHOOL)))).doesNotContain("2027-05-24");
        assertThat(PushProbe.sentTo(pushes, phone)).as("a cancellation is not a second push").hasSize(1);
    }

    // ---------------------------------------------------------------- what a request may carry

    @Test void an_academy_or_a_grade_the_school_does_not_teach_is_refused() throws Exception {
        clock.pinTo(at("2027-05-25", "09:00"));
        badRequest("{\"date\":\"2027-05-26\",\"academy\":\"french\",\"reason\":\"x\"}");
        badRequest("{\"date\":\"2027-05-26\",\"grades\":[9],\"reason\":\"x\"}");
        badRequest("{\"date\":\"2027-05-24\",\"reason\":\"yesterday\"}");
        badRequest("{\"date\":\"2027-05-26\",\"reason\":\"   \"}");
    }

    // ---------------------------------------------------------------- who may ask

    @Test void only_the_admin_declares_or_cancels_a_day_and_a_manager_may_read_the_list() throws Exception {
        clock.pinTo(at("2027-05-27", "09:00"));
        String body = "{\"date\":\"2027-05-28\",\"reason\":\"Maintenance\"}";
        forbidden(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON).content(body), token(MANAGER));
        forbidden(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON).content(body), token(BRITISH_1));
        forbidden(get("/admin/remote-study-days"), token(BRITISH_1));
        mvc.perform(get("/admin/remote-study-days").header("Authorization", bearer(PARENT_B1))).andExpect(status().isForbidden());

        String id = ok(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON).content(body), admin(), SCHOOL)).get("id").asText();
        assertThat(ok(bearer(get("/admin/remote-study-days"), token(MANAGER))).get("days")).as("she runs the school's day").isNotEmpty();
        forbidden(delete("/admin/remote-study-days/" + id), token(MANAGER));
        mvc.perform(scoped(delete("/admin/remote-study-days/" + id), admin(), SCHOOL)).andExpect(status().isNoContent());
    }

    @Test void the_routes_are_gone_while_the_flag_is_off() throws Exception {
        clock.pinTo(at("2027-05-29", "09:00"));
        flags(SCHOOL, false);
        try {
            mvc.perform(scoped(get("/admin/remote-study-days"), admin(), SCHOOL)).andExpect(status().isNotFound());
            mvc.perform(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"2027-05-30\",\"reason\":\"x\"}"), admin(), SCHOOL)).andExpect(status().isNotFound());
            mvc.perform(scoped(delete("/admin/remote-study-days/whatever"), admin(), SCHOOL)).andExpect(status().isNotFound());
        } finally {
            flags(SCHOOL, true);
        }
    }

    @Test void another_school_never_sees_or_cancels_this_school_s_days() throws Exception {
        clock.pinTo(at("2027-06-01", "09:00"));
        String id = ok(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2027-06-02\",\"reason\":\"Exam marking\"}"), admin(), SCHOOL)).get("id").asText();
        try {
            assertThat(ok(scoped(get("/admin/remote-study-days"), admin(), OTHER)).get("days")).isEmpty();
            mvc.perform(scoped(delete("/admin/remote-study-days/" + id), admin(), OTHER)).andExpect(status().isNotFound());
            assertThat(studyDays.findById(id)).as("and it is still there").isPresent();
        } finally {
            mvc.perform(scoped(delete("/admin/remote-study-days/" + id), admin(), SCHOOL)).andExpect(status().isNoContent());
        }
    }

    // ---------------------------------------------------------------- the two screens that read it

    @Test void the_child_s_today_and_the_teacher_s_class_attendance_both_say_it_is_a_remote_study_day() throws Exception {
        clock.pinTo(at("2027-06-04", "09:00"));
        ok(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2027-06-04\",\"academy\":\"british\",\"grades\":[1],\"reason\":\"Flooding\"}"), admin(), SCHOOL));

        var today = ok(get("/children/" + childB1 + "/attendance/today").header("Authorization", bearer(PARENT_B1)));
        assertThat(today.get("remoteStudyDay").asBoolean()).isTrue();
        assertThat(today.get("date").asText()).isEqualTo("2027-06-04");
        assertThat(today.get("status").asText()).as("nobody marks a roster that is at home").isEqualTo("NOT_MARKED");
        assertThat(ok(get("/children/" + childA1 + "/attendance/today").header("Authorization", bearer(PARENT_A1))).isNull())
                .as("another academy: nothing to say").isTrue();

        var mine = ok(bearer(get("/teacher/classes/" + SECTION_B1 + "/attendance?date=2027-06-04"), token(BRITISH_1)));
        assertThat(mine.get("remoteStudyDay").asBoolean()).isTrue();
        var other = ok(bearer(get("/teacher/classes/" + SECTION_A1 + "/attendance?date=2027-06-04"), token(AMERICAN_1)));
        assertThat(other.get("remoteStudyDay").asBoolean()).isFalse();
        var another = ok(bearer(get("/teacher/classes/" + SECTION_B1 + "/attendance?date=2027-06-05"), token(BRITISH_1)));
        assertThat(another.get("remoteStudyDay").asBoolean()).as("only that day").isFalse();
    }

    // ---------------------------------------------------------------- helpers

    /** The `INSERT … SELECT` of `V46__remote_study_days.sql`, exactly as the migration ships it. */
    private static String copyStatement() throws Exception {
        String migration = new String(new ClassPathResource("db/migration/V46__remote_study_days.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int from = migration.indexOf("INSERT INTO remote_study_days");
        assertThat(from).as("V46 copies feature 18's rows").isGreaterThan(0);
        return migration.substring(from, migration.indexOf(';', from));
    }

    private void approveOwnRemoteDay(String userId, String date) throws Exception {
        String id = ok(bearer(post("/staff/attendance/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"REMOTE_DAY\",\"date\":\"" + date + "\",\"reason\":\"Plumber at home\"}"), token(userId))).get("id").asText();
        assertThat(ok(scoped(post("/attendance/requests/" + id + "/approve"), admin(), SCHOOL)).get("status").asText()).isEqualTo("APPROVED");
    }

    private JsonNode parentFeed(String parentUid, String childId) throws Exception {
        return json(mvc.perform(get("/children/" + childId + "/broadcasts").header("Authorization", bearer(parentUid)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode checkIn(String token, double lat, String installId) throws Exception {
        return ok(bearer(post("/staff/attendance/check-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":" + lat + ",\"longitude\":" + LON + ",\"accuracyMeters\":10,\"mocked\":false,\"installId\":\"" + installId + "\"}"), token));
    }

    private Entities.StaffCheckInEntity record(String userId, String date) {
        return records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(SCHOOL, "USER", userId, LocalDate.parse(date)).orElseThrow();
    }

    /** Feature 18's staff notice, for one employee and one day — unchanged in wording, dedupe and link by feature 23. */
    private int notices(String userId, String date) {
        Integer n = jdbc.queryForObject("select count(*) from notifications where user_id = ? and kind = ? and link like ?",
                Integer.class, userId, "attendance.remote_day", "%" + date + "%");
        return n == null ? 0 : n;
    }

    /** The announcement a remote study day of {@code date} posted, found through the row that keeps its id. */
    private String announcementOf(String date) throws Exception {
        String id = dayOf(ok(scoped(get("/admin/remote-study-days"), admin(), SCHOOL)), date).get("id").asText();
        return studyDays.findById(id).orElseThrow().getBroadcastId();
    }

    private static JsonNode item(JsonNode feed, String broadcastId) {
        for (var row : feed.get("items")) if (broadcastId.equals(row.get("id").asText())) return row;
        throw new AssertionError(broadcastId + " is not on this feed: " + feed.get("items"));
    }

    private static List<String> ids(JsonNode feed) {
        var out = new java.util.ArrayList<String>();
        for (var row : feed.get("items")) out.add(row.get("id").asText());
        return out;
    }

    private static List<String> dates(JsonNode list) {
        var out = new java.util.ArrayList<String>();
        for (var row : list.get("days")) out.add(row.get("date").asText());
        return out;
    }

    private static JsonNode dayOf(JsonNode list, String date) {
        for (var row : list.get("days")) if (date.equals(row.get("date").asText())) return row;
        throw new AssertionError(date + " is not in the list");
    }

    private void badRequest(String body) throws Exception {
        mvc.perform(scoped(post("/admin/remote-study-days").contentType(MediaType.APPLICATION_JSON).content(body), admin(), SCHOOL))
                .andExpect(status().isBadRequest());
    }

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

    private static String bearer(String parentUid) { return "Bearer fake-token-" + parentUid; }

    private static Instant at(String date, String time) { return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(RIYADH).toInstant(); }

    private String admin() { return jwt.issue(ADMIN, ADMIN + "@rsd.test", "ADMIN", null).token(); }

    private String token(String userId) {
        var role = users.findById(userId).orElseThrow().getRole();
        return jwt.issue(userId, userId + "@rsd.test", role, SCHOOL).token();
    }

    private String child(String name, String sectionId, String curriculum, int grade, String parentUid) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", bearer(parentUid)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"" + curriculum
                        + "\",\"grade\":" + grade + ",\"schoolCode\":\"" + CODE + "\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
        children.findById(id).ifPresent(c -> { c.setClassId(sectionId); children.save(c); });
        return id;
    }

    private void school(String id, String code) {
        var s = schools.findById(id).orElseGet(SchoolEntity::new);
        s.setId(id); s.setName(id); s.setCode(code);
        s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2]");
        s.setStatus("active"); s.setTimezone("Asia/Riyadh");
        if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private void flags(String schoolId, boolean remoteStudyDays) {
        for (String key : List.of(FlagKeys.STAFF_ATTENDANCE, FlagKeys.STAFF_MOBILE_APP, FlagKeys.ANNOUNCEMENTS)) flag(schoolId, key, true);
        flag(schoolId, FlagKeys.REMOTE_STUDY_DAYS, remoteStudyDays);
        featureFlags.invalidate(schoolId);
    }

    private void flag(String schoolId, String key, boolean on) {
        var row = schoolFlags.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(on); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
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

    private void teacher(String id, String curriculum, String gradesJson) {
        user(id, SCHOOL, "TEACHER");
        var t = teachers.findById(id).orElseGet(quest.server.auth.Entities.TeacherEntity::new);
        t.setUserId(id); t.setSubjectsJson("[\"math\"]"); t.setCurriculum(curriculum); t.setGradesJson(gradesJson);
        t.setUpdatedAt(Instant.now());
        teachers.save(t);
    }

    private void user(String id, String schoolId, String role) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@rsd.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(id.toUpperCase(Locale.ROOT));
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }
}
