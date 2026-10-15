package quest.server.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.api.staff.AttendanceRequestStatus;
import quest.api.staff.AttendanceRequestType;
import quest.server.ApiTestSupport;
import quest.server.analysis.LlmClient;
import quest.server.analysis.SampleLlmClient;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.chat.ChatThreadRepository;
import quest.server.children.ChildRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.staffattendance.StaffAttendanceDayRepository;
import quest.server.staffattendance.StaffAttendanceRequestRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TenantContext;
import quest.server.workers.WorkerRepository;

/**
 * Feature 25 end to end: `GET /me/morning-brief` and {@link MorningBriefJob} — which numbers the brief holds, whose
 * people they are, what happens when the model invents one or the provider is down, that two runs are one brief, the
 * roles, the switch, the non-teaching day, and that nothing about a child or a parent reaches the provider.
 *
 * <p>Each test builds a school of its own ({@link #school}) in Asia/Riyadh on the default week (Sunday–Thursday),
 * with the clock pinned to <strong>Sunday 2026-10-18, 09:00 Riyadh</strong>. The previous teaching day is therefore
 * Thursday 2026-10-15, which is what the staff numbers and the children's rate are about:
 * <ul>
 *   <li>{@code director}: a MANAGERIAL user whose `staff_scopes` row names `british`;</li>
 *   <li>Thursday's own `staff_attendance_days`: the British teacher 20 minutes late, the American teacher absent,
 *       and the worker without a check-out — one of each, in three different academies' worth of scope;</li>
 *   <li>two pending requests, one the director decides and one she does not;</li>
 *   <li>two complaints older than a day, one on a British child and one on an American child, plus a fresh one;</li>
 *   <li>one live event, posted by the director;</li>
 *   <li>Thursday's register: the two British children present and late, the American child absent.</li>
 * </ul>
 * So the Admin's brief is 1 / 1 / 1, two requests, two complaints and 67 %, and the director's is 1 / 0 / 0, one
 * request, one complaint and 100 %.
 *
 * <p>{@link LlmClient} is mocked for every test here, and its default answer <em>is</em> the sample client's, which
 * copies the server's own fact lines back: that is the accepting path of the validator, and the tests that want the
 * rejecting path invent a number or throw instead.
 */
class MorningBriefApiTest extends ApiTestSupport {
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    private static final Instant NOW = SUNDAY.atTime(9, 0).atZone(RIYADH).toInstant();

    @MockitoBean LlmClient llm;
    @Autowired MorningBriefJob job;
    @Autowired MorningBriefRepository briefs;
    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired StaffScopeRepository scopes;
    @Autowired WorkerRepository workers;
    @Autowired ClassRepository classes;
    @Autowired ChildRepository children;
    @Autowired quest.server.attendance.AttendanceRepository register;
    @Autowired quest.server.broadcasts.BroadcastRepository broadcasts;
    @Autowired ChatThreadRepository threadRows;
    @Autowired StaffAttendanceDayRepository attendanceDays;
    @Autowired quest.server.staffattendance.RemoteStudyDayRepository remoteStudyDays;
    @Autowired StaffAttendanceRequestRepository requests;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AdminJwtService jwt;

    @BeforeEach void pinAndAnswerLikeTheSampleClient() {
        clock.pinTo(NOW);
        when(llm.name()).thenReturn("test-provider");
        var sample = new SampleLlmClient();
        // `doAnswer(...).when(...)`, never `when(mock.call(...))`: the second form calls the mock to build the
        // matcher, which would run the answer above (or below) on three nulls before the test has even begun.
        doAnswer(call -> sample.complete(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(llm).complete(any(), any(), any());
    }

    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- the facts, and whose they are

    @Test void the_admins_brief_counts_the_whole_school() throws Exception {
        var s = school("adm");
        var brief = ok(scoped(get("/me/morning-brief"), s.admin(), s.id));

        assertThat(brief.get("day").asText()).as("the school's day, not the server's").isEqualTo(SUNDAY.toString());
        assertThat(brief.get("source").asText()).isEqualTo(MorningBriefDto.SOURCE_AI);

        var facts = brief.get("facts");
        assertThat(facts.get("scope").asText()).isEqualTo("SCHOOL");
        assertThat(facts.get("yesterday").asText()).as("the previous teaching day, not literally yesterday")
                .isEqualTo(THURSDAY.toString());
        assertThat(facts.get("lateYesterday").asInt()).isEqualTo(1);
        assertThat(facts.get("absentYesterday").asInt()).isEqualTo(1);
        assertThat(facts.get("missingCheckoutYesterday").asInt()).isEqualTo(1);
        assertThat(facts.get("requestsWaiting").asInt()).isEqualTo(2);
        assertThat(facts.get("overdueComplaints").asInt()).isEqualTo(2);
        assertThat(facts.get("eventsToday").asInt()).isEqualTo(1);
        assertThat(facts.get("childAttendanceRate").asInt()).as("(2 present or late of 3 marked)").isEqualTo(67);
        assertThat(facts.get("remoteStudyDayToday").asBoolean()).as("an ordinary Sunday").isFalse();
        assertThat(facts.get("remoteStudyDayReason").isNull()).isTrue();

        assertThat(lines(brief.get("linesEn"))).hasSizeBetween(MorningBriefService.MIN_LINES, MorningBriefService.MAX_LINES);
        assertThat(lines(brief.get("linesAr"))).hasSameSizeAs(lines(brief.get("linesEn")));
        assertThat(lines(brief.get("linesEn"))).allSatisfy(l -> assertThat(l).hasSizeLessThanOrEqualTo(MorningBriefService.MAX_LINE));
    }

    @Test void the_managers_brief_counts_only_her_academy() throws Exception {
        var s = school("mgr");
        var brief = ok(bearer(get("/me/morning-brief"), s.token(s.director, "MANAGERIAL")));
        var facts = brief.get("facts");

        assertThat(facts.get("scope").asText()).isEqualTo("ACADEMY");
        assertThat(facts.get("lateYesterday").asInt()).as("her own British teacher").isEqualTo(1);
        assertThat(facts.get("absentYesterday").asInt()).as("the American academy's absence is not hers").isZero();
        assertThat(facts.get("missingCheckoutYesterday").asInt()).as("nor the worker's missing check-out").isZero();
        assertThat(facts.get("requestsWaiting").asInt()).as("only the request she decides").isEqualTo(1);
        assertThat(facts.get("overdueComplaints").asInt()).as("only the complaint in her reach").isEqualTo(1);
        assertThat(facts.get("childAttendanceRate").asInt()).as("her two British children, both marked in").isEqualTo(100);
    }

    @Test void a_school_with_nothing_in_it_still_has_a_brief_and_no_invented_rate() throws Exception {
        var s = bareSchool("calm");
        var brief = ok(scoped(get("/me/morning-brief"), s.admin(), s.id));
        var facts = brief.get("facts");

        assertThat(facts.get("lateYesterday").asInt()).isZero();
        assertThat(facts.get("requestsWaiting").asInt()).isZero();
        assertThat(facts.get("overdueComplaints").asInt()).isZero();
        assertThat(facts.get("eventsToday").asInt()).isZero();
        assertThat(facts.get("childAttendanceRate").isNull()).as("no register taken is null, never 0 %").isTrue();
        assertThat(lines(brief.get("linesEn"))).hasSizeGreaterThanOrEqualTo(MorningBriefService.MIN_LINES);
    }

    // ---------------------------------------------------------------- feature 23's remote study day

    @Test void a_remote_study_day_reaches_the_academy_it_was_declared_for_and_the_admin() throws Exception {
        var s = school("remote");
        s.flag(FlagKeys.REMOTE_STUDY_DAYS, true);
        s.remoteStudyDay("british", "Sandstorm warning");

        var admin = ok(scoped(get("/me/morning-brief"), s.admin(), s.id)).get("facts");
        assertThat(admin.get("remoteStudyDayToday").asBoolean()).as("the Admin reaches every academy").isTrue();
        assertThat(admin.get("remoteStudyDayReason").asText()).isEqualTo("Sandstorm warning");

        var british = ok(bearer(get("/me/morning-brief"), s.token(s.director, "MANAGERIAL"))).get("facts");
        assertThat(british.get("remoteStudyDayToday").asBoolean()).as("hers, by academy alone").isTrue();
        assertThat(british.get("remoteStudyDayReason").asText()).isEqualTo("Sandstorm warning");

        var other = school("remote-other");
        other.flag(FlagKeys.REMOTE_STUDY_DAYS, true);
        other.remoteStudyDay("american", "Flooding");
        assertThat(ok(bearer(get("/me/morning-brief"), other.token(other.director, "MANAGERIAL")))
                .get("facts").get("remoteStudyDayToday").asBoolean())
                .as("the American academy's day is not the British manager's").isFalse();
    }

    @Test void a_remote_study_day_is_in_the_lines_and_nowhere_when_the_switch_is_off() throws Exception {
        var s = school("remote-off");
        s.remoteStudyDay("british", "Sandstorm warning");                          // `remoteStudyDays` still off
        var off = ok(scoped(get("/me/morning-brief"), s.admin(), s.id));
        assertThat(off.get("facts").get("remoteStudyDayToday").asBoolean())
                .as("with the switch off a remote study day does not exist at all").isFalse();

        var on = school("remote-lines");
        on.flag(FlagKeys.REMOTE_STUDY_DAYS, true);
        on.remoteStudyDay(null, "Sandstorm warning");                              // the whole school
        doThrow(new LlmClient.LlmException("provider is busy", null, true)).when(llm).complete(any(), any(), any());
        var brief = ok(scoped(get("/me/morning-brief"), on.admin(), on.id));
        assertThat(brief.get("source").asText()).isEqualTo(MorningBriefDto.SOURCE_TEMPLATE);
        assertThat(lines(brief.get("linesEn"))).contains("Today is a remote study day.");
        assertThat(lines(brief.get("linesAr"))).hasSameSizeAs(lines(brief.get("linesEn")));
        assertThat(lines(brief.get("linesEn"))).as("still a glance, with six facts to choose from")
                .hasSizeLessThanOrEqualTo(MorningBriefService.MAX_LINES);
    }

    // ---------------------------------------------------------------- when the model cannot be trusted

    @Test void an_invented_number_falls_back_to_the_template() throws Exception {
        var s = school("made-up");
        doReturn(new LlmClient.Result(
                "{\"linesAr\":[\"تأخر ٩ موظفين\",\"طلبان ينتظران قرارك\",\"شكويان قديمتان\"],"
                        + "\"linesEn\":[\"9 employees arrived late.\",\"2 requests are waiting.\",\"2 complaints are old.\"]}",
                100, 50)).when(llm).complete(any(), any(), any());

        var brief = ok(scoped(get("/me/morning-brief"), s.admin(), s.id));
        assertThat(brief.get("source").asText()).as("9 is not one of the facts, so nothing of that answer is kept")
                .isEqualTo(MorningBriefDto.SOURCE_TEMPLATE);
        assertThat(lines(brief.get("linesEn"))).noneMatch(l -> l.contains("9 employees"));
        assertThat(lines(brief.get("linesEn")).getFirst()).contains("1 late", "1 absent");
        assertThat(lines(brief.get("linesAr"))).isNotEmpty();
        assertThat(brief.get("facts").get("lateYesterday").asInt()).as("the facts are the server's either way").isEqualTo(1);
    }

    @Test void a_provider_failure_falls_back_to_the_template_and_never_fails_the_request() throws Exception {
        var s = school("down");
        doThrow(new LlmClient.LlmException("provider is busy", null, true)).when(llm).complete(any(), any(), any());

        var brief = ok(scoped(get("/me/morning-brief"), s.admin(), s.id));
        assertThat(brief.get("source").asText()).isEqualTo(MorningBriefDto.SOURCE_TEMPLATE);
        assertThat(lines(brief.get("linesEn"))).hasSizeGreaterThanOrEqualTo(MorningBriefService.MIN_LINES);
        assertThat(lines(brief.get("linesAr"))).hasSameSizeAs(lines(brief.get("linesEn")));
    }

    /** An Arabic-Indic number that <em>is</em> a fact is the same number, so it must not be rejected for its digits. */
    @Test void arabic_indic_digits_of_a_real_fact_are_accepted() throws Exception {
        var s = school("indic");
        doReturn(new LlmClient.Result(
                "{\"linesAr\":[\"تأخر موظف واحد أمس\",\"طلبان ينتظران قرارك\",\"نسبة الحضور ٦٧ بالمئة\"],"
                        + "\"linesEn\":[\"1 employee arrived late.\",\"2 requests wait.\",\"Attendance was 67 per cent.\"]}",
                100, 50)).when(llm).complete(any(), any(), any());
        var brief = ok(scoped(get("/me/morning-brief"), s.admin(), s.id));
        assertThat(brief.get("source").asText()).isEqualTo(MorningBriefDto.SOURCE_AI);
        assertThat(lines(brief.get("linesAr"))).anyMatch(l -> l.contains("٦٧"));
    }

    // ---------------------------------------------------------------- privacy: who is never named

    @Test void no_child_parent_or_employee_is_named_to_the_model() throws Exception {
        var s = school("private");
        ok(scoped(get("/me/morning-brief"), s.admin(), s.id));

        var system = ArgumentCaptor.forClass(String.class);
        var user = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(system.capture(), user.capture(), any());
        String sent = system.getValue() + "\n" + user.getValue();
        assertThat(sent).doesNotContain("Nour", "Lina", "Adam", "Amy American", "تالا المعلمة", "Dalia Director",
                "Walid Worker", "parent@brief.test", "The bus is late");
        assertThat(sent).as("counts and labels only").contains("1 employees arrived late", "2 attendance requests");
    }

    // ---------------------------------------------------------------- the job

    @Test void the_job_run_twice_writes_one_brief_per_leader_and_day() {
        var s = school("job");
        int first = job.run(s.id);
        assertThat(first).as("at least the Admin and the director").isGreaterThanOrEqualTo(2);
        assertThat(job.run(s.id)).as("a rerun claims nothing and tells nobody").isZero();

        assertThat(briefs.findOneBySchoolIdAndUserIdAndDay(s.id, s.adminId, SUNDAY)).isPresent();
        assertThat(briefs.findOneBySchoolIdAndUserIdAndDay(s.id, s.director, SUNDAY)).isPresent();
        assertThat(briefs.findAll()).filteredOn(b -> s.id.equals(b.getSchoolId()) && s.director.equals(b.getUserId()))
                .as("one row, not two").hasSize(1);
    }

    @Test void the_endpoint_reads_the_job_s_brief_rather_than_generating_a_second_one() throws Exception {
        var s = school("once");
        job.run(s.id);
        org.mockito.Mockito.clearInvocations(llm);

        var brief = ok(bearer(get("/me/morning-brief"), s.token(s.director, "MANAGERIAL")));
        assertThat(brief.get("day").asText()).isEqualTo(SUNDAY.toString());
        verify(llm, never()).complete(any(), any(), any());
    }

    @Test void the_job_does_nothing_before_the_schools_own_quarter_past_seven() {
        var s = school("early");
        clock.pinTo(SUNDAY.atTime(6, 30).atZone(RIYADH).toInstant());
        assertThat(job.run(s.id)).isZero();
        assertThat(briefs.findOneBySchoolIdAndUserIdAndDay(s.id, s.director, SUNDAY)).isEmpty();
    }

    // ---------------------------------------------------------------- the day, the roles and the switch

    @Test void a_day_the_school_does_not_teach_has_no_brief_at_all() throws Exception {
        var s = school("friday");
        clock.pinTo(FRIDAY.atTime(9, 0).atZone(RIYADH).toInstant());
        mvc.perform(scoped(get("/me/morning-brief"), s.admin(), s.id)).andExpect(status().isNoContent());
        mvc.perform(bearer(get("/me/morning-brief"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isNoContent());
        assertThat(job.run(s.id)).isZero();
    }

    @Test void a_teacher_a_coordinator_a_worker_and_a_parent_never_reach_the_brief() throws Exception {
        var s = school("roles");
        mvc.perform(bearer(get("/me/morning-brief"), s.token(s.british, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/me/morning-brief"), s.token(s.director, "COORDINATOR"))).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/me/morning-brief"), s.token(s.workerAccount, "WORKER"))).andExpect(status().isForbidden());
        mvc.perform(get("/me/morning-brief").header("Authorization", PARENT)).andExpect(status().isForbidden());
    }

    @Test void a_school_with_the_switch_off_has_no_brief() throws Exception {
        var s = school("off");
        s.flag(FlagKeys.MORNING_BRIEF, false);
        mvc.perform(scoped(get("/me/morning-brief"), s.admin(), s.id)).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/me/morning-brief"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isNotFound());
        assertThat(job.run(s.id)).as("and the job skips it too").isZero();
    }

    // ---------------------------------------------------------------- school isolation

    @Test void another_schools_numbers_and_rows_never_appear_in_this_ones_brief() throws Exception {
        var mine = school("mine");
        var theirs = school("theirs");
        var alsoLate = attendanceDays.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(theirs.id, "USER", theirs.american, THURSDAY).orElseThrow();
        alsoLate.setLateMinutes(45); alsoLate.setAbsent(false); alsoLate.setNotArrived(false);
        attendanceDays.save(alsoLate);                                             // two late teachers, over there

        var brief = ok(bearer(get("/me/morning-brief"), mine.token(mine.director, "MANAGERIAL")));
        assertThat(brief.get("facts").get("lateYesterday").asInt()).as("hers, not both schools'").isEqualTo(1);
        assertThat(brief.get("facts").get("overdueComplaints").asInt()).isEqualTo(1);

        job.run(theirs.id);
        assertThat(briefs.findOneBySchoolIdAndUserIdAndDay(mine.id, theirs.director, SUNDAY))
                .as("naming the other school's leader answers nothing").isEmpty();
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

    private static List<String> lines(JsonNode rows) {
        var out = new ArrayList<String>();
        rows.forEach(r -> out.add(r.asText()));
        return out;
    }

    private static Instant hoursAgo(long hours) { return NOW.minus(java.time.Duration.ofHours(hours)); }

    /** A school with the four switches on and nobody in it yet. */
    private Fixture bareSchool(String label) {
        String id = "f25-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id);
        row.setCode(id.replace("-", "").toUpperCase(Locale.ROOT).substring(0, Math.min(20, id.replace("-", "").length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var f = new Fixture(id);
        f.flag(FlagKeys.MORNING_BRIEF, true); f.flag(FlagKeys.STAFF_ATTENDANCE, true);
        f.flag(FlagKeys.CHAT, true); f.flag(FlagKeys.ANNOUNCEMENTS, true);
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
        String adminId, director, british, american, workerAccount, worker;
        String britishClass, americanClass;
        String britishRequest, americanRequest;

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
            workerAccount = user(id + "-worker-account", id, "WORKER", "Walid Worker");
            worker = id + "-worker";
            var w = new quest.server.workers.Entities.WorkerEntity();
            w.setId(worker); w.setSchoolId(id); w.setFullName("Walid Worker"); w.setJob("caretaker");
            w.setActive(true); w.setUserId(workerAccount); w.setCreatedAt(Instant.now());
            workers.save(w);

            // Thursday, the previous teaching day: one late, one absent, one without a check-out.
            attendanceDay(british, 20, false, false);
            attendanceDay(american, null, true, false);
            workerAttendanceDay(worker);

            britishRequest = request(british, SUNDAY.plusDays(1));
            americanRequest = request(american, SUNDAY.plusDays(1));

            britishClass = section(id + ":british:1:math", "british", 1, british);
            americanClass = section(id + ":american:1:math", "american", 1, american);
            String nour = child(id + "-nour", "Nour", britishClass, "british");
            String lina = child(id + "-lina", "Lina", britishClass, "british");
            String adam = child(id + "-adam", "Adam", americanClass, "american");

            complaint(nour, british, "The bus is late", hoursAgo(30));
            complaint(adam, american, "The trip fee", hoursAgo(26));
            complaint(lina, british, "The homework app", hoursAgo(3));

            // Thursday's register: two British children in, the American child out — 2 of 3 marked present or late.
            mark(nour, britishClass, "PRESENT");
            mark(lina, britishClass, "LATE");
            mark(adam, americanClass, "ABSENT");

            event(director, "Open day");
        }

        String admin() { return jwt.issue(adminId, adminId + "@brief.test", "ADMIN", null).token(); }

        String token(String userId, String role) { return jwt.issue(userId, userId + "@brief.test", role, id).token(); }

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
            u.setId(userId); u.setSchoolId(schoolId); u.setEmail(userId + "@brief.test"); u.setPasswordHash("x");
            u.setRole(role); u.setStatus("active"); u.setDisplayName(name);
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            users.save(u);
            return userId;
        }

        void attendanceDay(String staffId, Integer lateMinutes, boolean absent, boolean missingCheckout) {
            save("USER", staffId, lateMinutes, absent, missingCheckout);
        }

        void workerAttendanceDay(String workerId) { save("WORKER", workerId, null, false, true); }

        private void save(String kind, String staffId, Integer lateMinutes, boolean absent, boolean missingCheckout) {
            var row = new quest.server.staffattendance.Entities.StaffAttendanceDayEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id);
            row.setStaffKind(kind); row.setStaffId(staffId); row.setDay(THURSDAY);
            row.setLateMinutes(lateMinutes); row.setNotArrived(absent); row.setAbsent(absent);
            row.setMissingCheckout(missingCheckout); row.setUpdatedAt(NOW);
            attendanceDays.save(row);
        }

        String request(String staffId, LocalDate day) {
            var row = new quest.server.staffattendance.Entities.StaffAttendanceRequestEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(id);
            row.setStaffKind("USER"); row.setStaffId(staffId);
            row.setType(AttendanceRequestType.REMOTE_DAY); row.setDay(day);
            row.setReason("Working from home"); row.setStatus(AttendanceRequestStatus.PENDING);
            row.setCreatedBy(staffId); row.setCreatedAt(NOW.minusSeconds(3600));
            requests.save(row);
            return row.getId();
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
            c.setParentEmail("parent@brief.test"); c.setActive(true); c.setCreatedAt(Instant.now());
            children.save(c);
            return childId;
        }

        void mark(String childId, String sectionId, String status) {
            var a = new quest.server.attendance.AttendanceEntity();
            a.setId(UUID.randomUUID().toString()); a.setSchoolId(id); a.setSectionId(sectionId);
            a.setChildId(childId); a.setDate(THURSDAY); a.setStatus(status); a.setMarkedBy(british);
            a.setCreatedAt(NOW); a.setUpdatedAt(NOW);
            register.save(a);
        }

        void complaint(String childId, String staffId, String title, Instant createdAt) {
            var t = new quest.server.chat.Entities.ChatThreadEntity();
            t.setId(UUID.randomUUID().toString()); t.setSchoolId(id); t.setChildId(childId);
            t.setTeacherId(staffId); t.setStaffRole("TEACHER"); t.setTopic("complaint"); t.setStatus("open");
            t.setThreadKey(t.getId()); t.setTitle(title); t.setCreatedAt(createdAt); t.setLastMessageAt(createdAt);
            threadRows.save(t);
        }

        /** Feature 23: one remote study day today, for one academy or — with a null academy — the whole school. */
        void remoteStudyDay(String academy, String reason) {
            var d = new quest.server.staffattendance.Entities.RemoteStudyDayEntity();
            d.setId(UUID.randomUUID().toString()); d.setSchoolId(id); d.setDay(SUNDAY);
            d.setAcademy(academy); d.setReason(reason); d.setCreatedBy(adminId); d.setCreatedAt(NOW.minusSeconds(86400));
            remoteStudyDays.save(d);
        }

        void event(String authorId, String title) {
            var b = new quest.server.broadcasts.Entities.BroadcastEntity();
            b.setId(UUID.randomUUID().toString()); b.setSchoolId(id); b.setAuthorUserId(authorId);
            b.setAuthorRole("MANAGERIAL"); b.setKind("event"); b.setTitle(title);
            b.setBodyEn("The open day is today."); b.setBodyAr("اليوم المفتوح اليوم");
            b.setAudienceRoles("parents"); b.setCreatedAt(NOW.minusSeconds(7200));
            broadcasts.save(b);
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
