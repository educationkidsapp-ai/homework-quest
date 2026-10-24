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
    private static final String CLASS_A = A + ":british:1:math", CLASS_B = B + ":british:1:math";
    private static final String PANEL = "{\"objectives\":{\"en\":[\"Count\"],\"ar\":[\"Count\"]},\"supported\":[],\"challenge\":[],\"stopTips\":[],\"modelAnswers\":[]}";
    private static final LocalDate DATE_A = LocalDate.of(2027, 4, 7), DATE_B = LocalDate.of(2027, 4, 9);

    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository assignments;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired LessonRepository lessons;
    @Autowired AdminJwtService jwt;
    @Autowired TenantContext tenant;
    @Autowired AdminLessonService lessonService;
    @Autowired ChildService childService;
    @Autowired quest.server.workers.WorkerRepository workers;
    @Autowired quest.server.push.StaffDeviceRepository staffDevices;
    @Autowired quest.server.staffattendance.StaffAttendanceDayRepository attendanceDays;
    @Autowired quest.server.staffattendance.StaffAttendanceAlertRepository attendanceAlerts;
    @Autowired quest.server.staffattendance.RemoteStudyDayRepository remoteDays;
    @Autowired quest.server.staffattendance.StaffAttendanceRequestRepository attendanceRequests;
    @Autowired quest.server.chat.ChatAfterHoursRepository afterHours;
    @Autowired quest.server.floors.FloorRepository floors;
    @Autowired quest.server.floors.FloorClassRepository floorClasses;
    @Autowired quest.server.floors.FloorSupervisorRepository floorSupervisors;
    @Autowired quest.server.broadcasts.AnnouncementDraftRepository drafts;
    @Autowired quest.server.ai.AiCallRepository aiCalls;
    @Autowired quest.server.brief.MorningBriefRepository morningBriefs;
    @Autowired quest.server.documents.DocumentSummaryRepository documentSummaries;
    @Autowired quest.server.chat.ChatThreadRepository chatThreads;
    @Autowired quest.server.chat.ComplaintTriageRepository complaintTriage;
    @Autowired quest.server.children.EveningCardRepository eveningCards;
    @Autowired quest.server.translate.TranslationRepository translations;
    @Autowired quest.server.hints.StopHintsRepository stopHints;
    @Autowired quest.server.hints.PracticePlayRepository practicePlays;
    @Autowired quest.server.content.PlayRepository playRows;
    @Autowired quest.server.materials.MaterialSettingsRepository materialSettings;
    @Autowired quest.server.materials.MaterialSubmissionRepository materialSubmissions;
    @Autowired quest.server.materials.MaterialReminderRepository materialReminders;
    @Autowired quest.server.chat.ClassChannelRepository classChannels;
    @Autowired quest.server.evaluations.EvaluationRoundRepository evaluationRounds;
    @Autowired quest.server.evaluations.ChildEvaluationRepository childEvaluations;
    @Autowired quest.server.staffattendance.LeaveTypeRepository leaveTypes;
    @Autowired quest.server.staffattendance.LeaveBalanceRepository leaveBalances;
    @Autowired quest.server.evaluations.EvaluationReminderRepository evaluationReminders;
    @Autowired quest.server.chat.ChannelReadRepository channelReads;

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

    /**
     * Feature 13's two new tenant rows. A worker of B is not found by an Admin acting in A — the lookup is a filtered
     * query, as every worker lookup is — and a phone registered by a teacher of A carries A, taken from her token and
     * never from the request, so a push to a school's staff can only ever reach that school's phones.
     */
    @Test void feature_13s_worker_accounts_and_staff_phones_carry_the_callers_school() throws Exception {
        var row = new quest.server.workers.Entities.WorkerEntity();
        row.setId("iso-worker-b"); row.setSchoolId(B); row.setFullName("Samir Nour"); row.setJob("Driver");
        row.setActive(true); row.setCreatedAt(java.time.Instant.now());
        workers.save(row);
        String admin = adminToken(), phone = "iso-staff-phone-" + java.util.UUID.randomUUID();
        try {
            mvc.perform(scoped(post("/admin/workers/iso-worker-b/app-access"), admin, A)).andExpect(status().isNotFound());
            mvc.perform(scoped(post("/admin/workers/iso-worker-b/app-access"), admin, B)).andExpect(status().isCreated());

            String teacher = jwt.issue("teacher-a", "teacher@alpha.test", "TEACHER", A).token();
            mvc.perform(post("/me/devices").header("Authorization", "Bearer " + teacher)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + phone + "\",\"platform\":\"ANDROID\"}"))
                    .andExpect(status().isNoContent());
            assertThat(staffDevices.findByToken(phone).orElseThrow().getSchoolId()).isEqualTo(A);
        } finally {
            staffDevices.deleteByTokenValue(phone);
            String account = workers.findById("iso-worker-b").map(quest.server.workers.Entities.WorkerEntity::getUserId).orElse(null);
            workers.deleteById("iso-worker-b");
            if (account != null) users.findById(account).ifPresent(users::delete);
        }
    }

    /**
     * Feature 16's two tenant tables. A status day and a sent-alert key of B are invisible to a principal of A: every
     * read goes through the `school` filter. The job itself names the school on every query and is proven not to write
     * across schools in `StaffAttendanceJobTest.one_schools_run_writes_and_tells_nothing_in_another`.
     */
    @Test void feature_16s_status_days_and_alert_keys_stay_in_their_school() {
        var now = java.time.Instant.now();
        var day = new quest.server.staffattendance.Entities.StaffAttendanceDayEntity();
        day.setId("iso-day-b"); day.setSchoolId(B); day.setStaffKind("USER"); day.setStaffId("teacher-b");
        day.setDay(DATE_B); day.setLateMinutes(12); day.setUpdatedAt(now);
        var alert = new quest.server.staffattendance.Entities.StaffAttendanceAlertEntity();
        alert.setId("iso-alert-b"); alert.setSchoolId(B); alert.setStaffKind("USER"); alert.setStaffId("teacher-b");
        alert.setDay(DATE_B); alert.setType("LATE"); alert.setSentAt(now);
        attendanceDays.save(day); attendanceAlerts.save(alert);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(attendanceDays.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(attendanceAlerts.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(attendanceDays.findBySchoolIdAndDay(A, DATE_B)).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(attendanceDays.findAll()).anyMatch(r -> "iso-day-b".equals(r.getId()));
        } finally {
            tenant.clear();
            attendanceDays.deleteById("iso-day-b"); attendanceAlerts.deleteById("iso-alert-b");
        }
    }

    /**
     * Feature 18's requests and feature 23's remote study days, which replaced its school-wide remote day. A
     * principal of A finds neither B's declared day nor B's request, and naming B's school herself answers nothing:
     * `remote_study_days` is read by the attendance job with no request behind it, so every query names a school
     * <em>and</em> the filter is on.
     */
    @Test void feature_18s_requests_and_feature_23s_remote_study_days_stay_in_their_school() {
        var now = java.time.Instant.now();
        var remote = new quest.server.staffattendance.Entities.RemoteStudyDayEntity();
        remote.setId("iso-remote-b"); remote.setSchoolId(B); remote.setDay(DATE_B); remote.setReason("Rain");
        remote.setAcademy("british"); remote.setGradesJson("[1]");
        remote.setCreatedBy("admin"); remote.setCreatedAt(now);
        var request = new quest.server.staffattendance.Entities.StaffAttendanceRequestEntity();
        request.setId("iso-request-b"); request.setSchoolId(B); request.setStaffKind("USER"); request.setStaffId("teacher-b");
        request.setType("CORRECTION"); request.setDay(DATE_B); request.setReason("Forgot"); request.setStatus("PENDING");
        request.setCreatedBy("teacher-b"); request.setCreatedAt(now);
        remoteDays.save(remote); attendanceRequests.save(request);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(remoteDays.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(attendanceRequests.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(remoteDays.findBySchoolIdAndDay(A, DATE_B)).isEmpty();
            assertThat(remoteDays.findBySchoolIdAndDay(B, DATE_B)).as("and B's own school is not hers to ask about").isEmpty();
            assertThat(remoteDays.findOneBySchoolIdAndId(B, "iso-remote-b")).isEmpty();
            assertThat(remoteDays.findBySchoolIdAndDayBetweenOrderByDayAsc(B, DATE_B, DATE_B)).isEmpty();
            assertThat(attendanceRequests.findOneBySchoolIdAndId(A, "iso-request-b")).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(attendanceRequests.findAll()).anyMatch(r -> "iso-request-b".equals(r.getId()));
            assertThat(remoteDays.findAll()).anyMatch(r -> "iso-remote-b".equals(r.getId()));
            assertThat(remoteDays.findOneBySchoolIdAndId(B, "iso-remote-b")).isPresent();
        } finally {
            tenant.clear();
            remoteDays.deleteById("iso-remote-b"); attendanceRequests.deleteById("iso-request-b");
        }
    }

    /**
     * Feature 19's tenant table. A message B counted as after-hours is invisible to a principal of A, and the summary
     * job itself names the school on every query — including the claim, which is a bulk update and so outside the
     * `school` filter: `ChatOfficeHoursApiTest` runs it for one school and reads only that school's rows.
     */
    @Test void feature_19s_after_hours_rows_stay_in_their_school() {
        var now = java.time.Instant.now();
        var row = new quest.server.chat.Entities.ChatAfterHoursEntity();
        row.setId("iso-after-hours-b"); row.setSchoolId(B); row.setUserId("teacher-b");
        row.setThreadId("iso-thread-b"); row.setMessageId("iso-message-b");
        row.setReceivedAt(now); row.setDueDay(DATE_B);
        afterHours.save(row);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(afterHours.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(afterHours.dueRecipients(A, DATE_B)).as("A owes nobody a summary").isEmpty();
            assertThat(afterHours.dueRecipients(B, DATE_B)).as("and cannot ask about B's either").isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(afterHours.findAll()).anyMatch(r -> "iso-after-hours-b".equals(r.getId()));
            assertThat(afterHours.dueRecipients(B, DATE_B)).containsExactly("teacher-b");
        } finally {
            tenant.clear();
            afterHours.deleteById("iso-after-hours-b");
        }
    }

    /**
     * Feature 28's cards. A card of B is invisible to a principal of A, and naming B's school herself answers nothing:
     * `evening_cards` is read on the parent side, which carries no tenant scope at all, and by the 18:00 job, which has
     * no request behind it — so every query names a school <em>and</em> the filter is on.
     */
    @Test void feature_28s_evening_cards_stay_in_their_school() throws Exception {
        var childB = parentPost("/children", "{\"name\":\"Basem\",\"avatarColor\":\"mint\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"SCHLBB\"}").get("id").asText();
        var card = new quest.server.children.Entities.EveningCardEntity();
        card.setId("iso-card-b"); card.setSchoolId(B); card.setChildId(childB); card.setCardDay(DATE_B);
        card.setFactsJson("{\"day\":\"" + DATE_B + "\",\"lessons\":[]}");
        card.setTextAr("ar"); card.setTextEn("en"); card.setSource("template"); card.setCreatedAt(Instant.now());
        eveningCards.save(card);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(eveningCards.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(eveningCards.findOneOfChild(B, childB, DATE_B)).as("and B's own school is not hers to ask about").isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(eveningCards.findOneOfChild(B, childB, DATE_B)).isPresent();
        } finally {
            tenant.clear();
            eveningCards.deleteById("iso-card-b");
        }
    }

    /**
     * Feature 20's three tenant tables. A floor of B, the class on it and the supervisor who holds it are invisible to
     * a principal of A — and `FloorScope` reads all three through these very queries, so a scope that leaked would
     * leak here first.
     */
    @Test void feature_20s_floors_stay_in_their_school() {
        var now = java.time.Instant.now();
        var floor = new quest.server.floors.Entities.FloorEntity();
        floor.setId("iso-floor-b"); floor.setSchoolId(B); floor.setName("Second floor"); floor.setAcademy("british");
        floor.setCreatedAt(now);
        var onFloor = new quest.server.floors.Entities.FloorClassEntity();
        onFloor.setId("iso-floor-class-b"); onFloor.setFloorId("iso-floor-b"); onFloor.setSchoolId(B);
        onFloor.setSectionId(CLASS_B); onFloor.setCreatedAt(now);
        var holder = new quest.server.floors.Entities.FloorSupervisorEntity();
        holder.setId("iso-floor-sup-b"); holder.setFloorId("iso-floor-b"); holder.setSchoolId(B);
        holder.setUserId("teacher-b"); holder.setCreatedAt(now);
        floors.save(floor); floorClasses.save(onFloor); floorSupervisors.save(holder);
        try {
            tenant.set("FLOOR_SUPERVISOR", A, null);
            assertThat(floors.findAll()).noneMatch(f -> B.equals(f.getSchoolId()));
            assertThat(floors.findOneById("iso-floor-b")).isEmpty();
            assertThat(floors.findBySchoolIdOrderByNameAsc(A)).isEmpty();
            assertThat(floorClasses.findBySchoolIdAndSectionId(A, CLASS_B)).as("A cannot ask about B's class either").isEmpty();
            assertThat(floorSupervisors.findBySchoolIdAndUserIdOrderByFloorIdAsc(A, "teacher-b")).isEmpty();
            tenant.set("FLOOR_SUPERVISOR", B, null);
            assertThat(floors.findOneById("iso-floor-b")).isPresent();
            assertThat(floorClasses.findBySchoolIdAndSectionId(B, CLASS_B)).isPresent();
            assertThat(floorSupervisors.findBySchoolIdAndUserIdOrderByFloorIdAsc(B, "teacher-b")).hasSize(1);
        } finally {
            tenant.clear();
            floorSupervisors.deleteById("iso-floor-sup-b"); floorClasses.deleteById("iso-floor-class-b");
            floors.deleteById("iso-floor-b");
        }
    }

    /**
     * Feature 22's tenant table. A draft is the most private row the platform holds — a leader's own words, prepared
     * and not yet sent — so it is scoped twice: by the `school` filter, and by the author inside it. A principal of A
     * finds neither B's draft nor B's author's, and naming B's school herself answers nothing either; the sweep is the
     * one unscoped read and names no school at all, which is why it is asserted from B's own side.
     */
    @Test void feature_22s_announcement_drafts_stay_in_their_school_and_with_their_author() {
        var row = new quest.server.broadcasts.Entities.AnnouncementDraftEntity();
        row.setId("iso-draft-b"); row.setSchoolId(B); row.setAuthorId("manager-b");
        row.setTranscript("Parents of grade one, the trip is on Thursday.");
        row.setTitle("Grade one trip"); row.setBodyAr("رحلة الصف الأول"); row.setBodyEn("The grade one trip is on Thursday.");
        row.setCreatedAt(Instant.now());
        drafts.save(row);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(drafts.findAll()).noneMatch(d -> B.equals(d.getSchoolId()));
            assertThat(drafts.findOneOfAuthor(A, "iso-draft-b", "manager-b")).as("not A's, whoever she names").isEmpty();
            assertThat(drafts.findOneOfAuthor(B, "iso-draft-b", "manager-b")).as("and B's own school is not hers to ask about").isEmpty();
            assertThat(drafts.byAuthor(A, "manager-b", org.springframework.data.domain.PageRequest.of(0, 20))).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(drafts.findOneOfAuthor(B, "iso-draft-b", "manager-b")).isPresent();
            assertThat(drafts.findOneOfAuthor(B, "iso-draft-b", "teacher-b")).as("another author of the same school is a 404 too").isEmpty();
            assertThat(drafts.byAuthor(B, "manager-b", org.springframework.data.domain.PageRequest.of(0, 20))).hasSize(1);
        } finally {
            tenant.clear();
            drafts.deleteById("iso-draft-b");
        }
    }

    /**
     * Feature 24's tenant table. `ai_calls` holds numbers only — no prompt and no answer — but what a school spends on
     * the AI is still a school's own business, so the ledger is filtered like every other tenant table. A principal of
     * A cannot read B's rows, and naming B's own tag answers nothing either.
     */
    @Test void feature_24s_ai_call_ledger_stays_in_its_school() {
        var row = new quest.server.ai.Entities.AiCallEntity();
        row.setId("iso-ai-call-b"); row.setSchoolId(B); row.setFeature("lessonAnalysis"); row.setTag("iso-A");
        row.setProvider("sample"); row.setInputTokens(1200); row.setOutputTokens(800);
        row.setOutcome(quest.server.ai.AiCallLedger.OK); row.setDurationMs(1234); row.setCreatedAt(Instant.now());
        aiCalls.save(row);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(aiCalls.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(aiCalls.findByTagOrderByCreatedAtAsc("iso-A")).as("not A's, whatever she asks for").isEmpty();
            assertThat(aiCalls.findByFeatureOrderByCreatedAtAsc("lessonAnalysis")).noneMatch(r -> "iso-ai-call-b".equals(r.getId()));
            tenant.set("MANAGERIAL", B, null);
            assertThat(aiCalls.findByTagOrderByCreatedAtAsc("iso-A")).extracting(r -> r.getId()).containsExactly("iso-ai-call-b");
        } finally {
            tenant.clear();
            aiCalls.deleteById("iso-ai-call-b");
        }
    }

    /**
     * Feature 25's tenant table. A brief holds a school's own numbers and the wording written from them, per leader
     * and per day, so it is scoped by the `school` filter like every other tenant table and by the leader inside it.
     * A principal of A finds neither B's brief nor B's leader's, and naming B's school herself answers nothing
     * either — which is also what makes the job's claim safe: it names the school it is running for.
     */
    @Test void feature_25s_morning_briefs_stay_in_their_school_and_with_their_leader() {
        var row = new quest.server.brief.Entities.MorningBriefEntity();
        row.setId("iso-brief-b"); row.setSchoolId(B); row.setUserId("director-b");
        row.setDay(LocalDate.of(2027, 4, 7));
        row.setFactsJson("{\"scope\":\"ACADEMY\",\"yesterday\":\"2027-04-06\",\"lateYesterday\":2}");
        row.setTextAr("أمس: 2 متأخرون"); row.setTextEn("Yesterday: 2 late");
        row.setSource("template"); row.setCreatedAt(Instant.now());
        morningBriefs.save(row);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(morningBriefs.findAll()).noneMatch(b -> B.equals(b.getSchoolId()));
            assertThat(morningBriefs.findOneBySchoolIdAndUserIdAndDay(A, "director-b", LocalDate.of(2027, 4, 7)))
                    .as("not A's, whoever she names").isEmpty();
            assertThat(morningBriefs.findOneBySchoolIdAndUserIdAndDay(B, "director-b", LocalDate.of(2027, 4, 7)))
                    .as("and B's own school is not hers to ask about").isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(morningBriefs.findOneBySchoolIdAndUserIdAndDay(B, "director-b", LocalDate.of(2027, 4, 7))).isPresent();
            assertThat(morningBriefs.findOneBySchoolIdAndUserIdAndDay(B, "teacher-b", LocalDate.of(2027, 4, 7)))
                    .as("another leader of the same school has her own brief, not this one").isEmpty();
        } finally {
            tenant.clear();
            morningBriefs.deleteById("iso-brief-b");
        }
    }

    /**
     * Feature 26's tenant table. A summary is what one leader read out of a document nobody else has — the document
     * itself is never stored — so it is scoped twice, as feature 22's draft is: by the `school` filter, and by the
     * author inside it. A principal of A finds neither B's summary nor B's author's, and naming B's school herself
     * answers nothing either; the retention sweep is the one unscoped read and names no school at all.
     */
    @Test void feature_26s_document_summaries_stay_in_their_school_and_with_their_author() {
        var row = new quest.server.documents.Entities.DocumentSummaryEntity();
        row.setId("iso-doc-b"); row.setSchoolId(B); row.setAuthorId("manager-b");
        row.setFileName("circular.pdf"); row.setPages(2);
        row.setSummaryJson("{\"title\":\"تعميم\",\"summaryAr\":\"تعميم\",\"summaryEn\":\"A circular.\"}");
        row.setCreatedAt(Instant.now());
        documentSummaries.save(row);
        try {
            var page = org.springframework.data.domain.PageRequest.of(0, 20);
            tenant.set("MANAGERIAL", A, null);
            assertThat(documentSummaries.findAll()).noneMatch(d -> B.equals(d.getSchoolId()));
            assertThat(documentSummaries.findOneOfAuthor(A, "iso-doc-b", "manager-b")).as("not A's, whoever she names").isEmpty();
            assertThat(documentSummaries.findOneOfAuthor(B, "iso-doc-b", "manager-b")).as("and B's own school is not hers to ask about").isEmpty();
            assertThat(documentSummaries.byAuthor(A, "manager-b", page)).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(documentSummaries.findOneOfAuthor(B, "iso-doc-b", "manager-b")).isPresent();
            assertThat(documentSummaries.findOneOfAuthor(B, "iso-doc-b", "teacher-b")).as("another author of the same school is a 404 too").isEmpty();
            assertThat(documentSummaries.byAuthor(B, "manager-b", page)).hasSize(1);
        } finally {
            tenant.clear();
            documentSummaries.deleteById("iso-doc-b");
        }
    }

    /**
     * Feature 29's tenant table. A translation holds what a parent or a teacher of <em>one</em> school wrote, in
     * another language — exactly the words the `chat_messages` row holds, which is why it is filtered like the source
     * is. A principal of A cannot read B's rows, and naming B's own source does not help her: the lookup takes a
     * school, and the one it is given is the source row's, resolved before the cache is ever asked.
     */
    @Test void feature_29s_translation_cache_stays_in_its_school() {
        var row = new quest.server.translate.Entities.TranslationEntity();
        row.setId("iso-translation-b"); row.setSchoolId(B);
        row.setSourceKind("chat"); row.setSourceId("iso-message-b"); row.setSourceHash("iso-hash");
        row.setLang("ur"); row.setText("[ur] a message of B's"); row.setCreatedAt(Instant.now());
        translations.save(row);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(translations.findAll()).noneMatch(t -> B.equals(t.getSchoolId()));
            assertThat(translations.of("chat", "iso-message-b")).as("not A's, whatever she names").isEmpty();
            assertThat(translations.hit(B, "chat", "iso-message-b", "ur", "iso-hash")).as("nor by naming B outright").isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(translations.of("chat", "iso-message-b")).extracting(r -> r.getId()).containsExactly("iso-translation-b");
            assertThat(translations.hit(B, "chat", "iso-message-b", "ur", "iso-hash")).isPresent();
        } finally {
            tenant.clear();
            translations.deleteById("iso-translation-b");
        }
    }

    @Test void feature_30s_hints_and_practice_sets_stay_in_their_school() {
        var hint = new quest.server.hints.Entities.StopHintsEntity();
        hint.setStopId("iso-stop-of-b"); hint.setLessonId(LESSON_B); hint.setSchoolId(B);
        hint.setHintsJson("[\"Look again.\",\"Count them first.\"]"); hint.setCreatedAt(Instant.now());
        stopHints.save(hint);
        // the practice row keys into `plays` (V52), so B gets a real practice play of its own to point at
        var playRow = new quest.server.content.Entities.PlayEntity();
        playRow.setId("iso-play-of-b"); playRow.setLessonId(LESSON_B); playRow.setLevel(1); playRow.setVariant(2);
        playRow.setPlayJson("{}"); playRow.setPromptVersion("v1"); playRow.setSeed(0); playRow.setGeneratedAt(Instant.now());
        playRows.save(playRow);
        var play = new quest.server.hints.Entities.PracticePlayEntity();
        play.setPlayId("iso-play-of-b"); play.setLessonId(LESSON_B); play.setSchoolId(B); play.setSkillId("iso-skill");
        // Approved, and signed: an unapproved row is empty to *every* tenant, B's own included, so the negative
        // below would have passed with the filter off and the positive proves the fixture really is there.
        play.setApprovedBy("iso-teacher-b"); play.setApprovedAt(Instant.now());
        play.setCreatedAt(Instant.now());
        practicePlays.save(play);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(stopHints.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(stopHints.findByLessonId(LESSON_B)).as("not A's lesson, so not A's hints").isEmpty();
            assertThat(stopHints.findByLessonIdIn(java.util.List.of(LESSON_A, LESSON_B))).isEmpty();
            assertThat(practicePlays.findByLessonIdOrderBySkillIdAsc(LESSON_B)).isEmpty();
            assertThat(practicePlays.approvedByLesson(java.util.List.of(LESSON_B))).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(stopHints.findByLessonId(LESSON_B)).extracting(r -> r.getStopId()).containsExactly("iso-stop-of-b");
            assertThat(practicePlays.findByLessonIdOrderBySkillIdAsc(LESSON_B)).extracting(r -> r.getPlayId())
                    .as("B's own practice set is B's to read").containsExactly("iso-play-of-b");
            assertThat(practicePlays.approvedByLesson(java.util.List.of(LESSON_B)))
                    .as("and the map's one query finds it, which is what the negative above denies A")
                    .extracting(row -> row[0] + "/" + row[1]).containsExactly(LESSON_B + "/iso-skill");
        } finally {
            tenant.clear();
            stopHints.deleteById("iso-stop-of-b");
            practicePlays.findById("iso-play-of-b").ifPresent(practicePlays::delete);
            playRows.findById("iso-play-of-b").ifPresent(playRows::delete);
        }
    }

    /**
     * Feature 31's three tenant tables. What a teacher prepared, the deadline her school holds her to, and the
     * reminders it has sent are that school's business: a principal of A reads none of B's rows, and naming B's own
     * school, teacher or week outright does not help her — the filter is on the query, not on the argument.
     */
    @Test void feature_31s_teaching_material_tables_stay_in_their_school() {
        var week = LocalDate.of(2026, 10, 18);
        var settings = new quest.server.materials.Entities.MaterialSettingsEntity();
        settings.setSchoolId(B); settings.setDueWeekday("WEDNESDAY");
        settings.setDueTime(java.time.LocalTime.of(10, 0)); settings.setRemindHoursBefore(3);
        settings.setUpdatedAt(Instant.now());
        materialSettings.save(settings);
        var handed = new quest.server.materials.Entities.MaterialSubmissionEntity();
        handed.setId("iso-material-b"); handed.setSchoolId(B); handed.setTeacherId("teacher-b");
        handed.setClassId(CLASS_B); handed.setSubject("math"); handed.setWeekStart(week);
        handed.setNote("B's own preparation"); handed.setSubmittedAt(Instant.now());
        materialSubmissions.save(handed);
        var reminder = new quest.server.materials.Entities.MaterialReminderEntity();
        reminder.setId("iso-material-reminder-b"); reminder.setSchoolId(B); reminder.setTeacherId("teacher-b");
        reminder.setWeekStart(week); reminder.setSentAt(Instant.now());
        materialReminders.save(reminder);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(materialSettings.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(materialSettings.findOneBySchoolId(B)).as("nor by naming B outright").isEmpty();
            assertThat(materialSubmissions.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(materialSubmissions.findBySchoolIdAndWeekStart(B, week)).isEmpty();
            assertThat(materialSubmissions.findOneBySchoolIdAndId(B, "iso-material-b")).isEmpty();
            assertThat(materialSubmissions.findFirstBySchoolIdAndAttachmentId(B, "iso-material-b")).isEmpty();
            assertThat(materialReminders.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(materialReminders.findBySchoolIdAndWeekStart(B, week)).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(materialSettings.findOneBySchoolId(B)).isPresent();
            assertThat(materialSubmissions.findBySchoolIdAndWeekStart(B, week))
                    .extracting(quest.server.materials.Entities.MaterialSubmissionEntity::getId).containsExactly("iso-material-b");
            assertThat(materialReminders.findBySchoolIdAndWeekStart(B, week)).hasSize(1);
        } finally {
            tenant.clear();
            materialReminders.deleteById("iso-material-reminder-b");
            materialSubmissions.deleteById("iso-material-b");
            materialSettings.deleteById(B);
        }
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

    /**
     * The routes above plus the collection, report and P3.0 dashboard routes: nothing a school-less dashboard token
     * may reach. The refusal comes from {@link TenantContext} through {@link TenantInterceptor}, before the handler,
     * so it is 403 on all of them whatever each one would otherwise have answered.
     */
    static Stream<Arguments> tenantRoutes() {
        return Stream.concat(
                Stream.of(Arguments.of("GET", "/admin/lessons"),
                        Arguments.of("GET", "/admin/lessons/" + LESSON_A),
                        Arguments.of("GET", "/admin/usage"),
                        Arguments.of("GET", "/admin/calendar?curriculum=british&grade=1&year=2027&month=4"),
                        // P3.0
                        Arguments.of("GET", "/me/home"),
                        Arguments.of("GET", "/admin/schools/" + A + "/classes"),
                        Arguments.of("GET", "/admin/schools/" + A + "/usage"),
                        Arguments.of("GET", "/admin/schools/" + A + "/billing"),
                        Arguments.of("GET", "/admin/usage/platform"),
                        Arguments.of("GET", "/school/usage"),
                        Arguments.of("GET", "/school/teachers"),
                        // P4.0
                        Arguments.of("GET", "/teacher/profile"),
                        Arguments.of("GET", "/teacher/options"),
                        Arguments.of("GET", "/teacher/classes/" + CLASS_A + "/calendar?year=2027&month=4"),
                        Arguments.of("GET", "/teacher/classes/" + CLASS_A + "/students"),
                        // Feature 14: the school's attendance rules and its staff's direct managers
                        Arguments.of("GET", "/admin/attendance-settings"),
                        Arguments.of("GET", "/admin/staff/attendance-profiles"),
                        // Feature 15: an employee's own record, and the phone approved for her. Every one of these
                        // answers about the caller's school and nothing names a school, so a principal who has none
                        // is refused before the handler rather than answered from the platform's defaults.
                        Arguments.of("GET", "/staff/attendance/today"),
                        Arguments.of("GET", "/staff/attendance/month"),
                        Arguments.of("GET", "/admin/staff/USER/teacher-a/attendance-device"),
                        // Feature 18: an employee's own requests and the decider's inbox. Feature 23: the Admin's
                        // remote study days, which replaced feature 18's school-wide remote day.
                        Arguments.of("GET", "/admin/remote-study-days"),
                        Arguments.of("GET", "/staff/attendance/requests"),
                        Arguments.of("GET", "/attendance/requests"),
                        Arguments.of("POST", "/attendance/requests/no-such-request/approve")),
                routesOfAnotherSchool());
    }

    // ---------------------------------------------------------------- P3.0: the dashboard's own routes

    /**
     * Every route P3.0 adds, from the other school's side: a Teacher or Managerial user of A asking about B gets a
     * 404 (the school is not hers to see) or a 403 (the permission is not hers at all) — never a row of B's.
     */
    static Stream<Arguments> dashboardRoutesOfAnotherSchool() {
        return Stream.of(
                Arguments.of("GET", "/admin/schools/" + B + "/classes"),
                Arguments.of("GET", "/admin/schools/" + B + "/usage"),
                Arguments.of("GET", "/admin/schools/" + B + "/billing"),
                Arguments.of("PATCH_CLASS", "/admin/schools/" + B + "/classes/" + B + ":british:1:math"),
                Arguments.of("CLASS", "/admin/schools/" + B + "/classes"),
                Arguments.of("GET", "/admin/usage/platform"));
    }

    @ParameterizedTest(name = "{0} {1} of another school is refused for a teacher and a managerial user of A")
    @MethodSource("dashboardRoutesOfAnotherSchool")
    void a_dashboard_route_of_another_school_is_refused(String method, String path) throws Exception {
        for (var principal : List.of(List.of("TEACHER", "teacher-a"), List.of("MANAGERIAL", "manager-a"))) {
            var token = jwt.issue(principal.get(1), principal.get(1) + "@alpha.test", principal.get(0), A).token();
            var result = mvc.perform(requestFor(method, path).header("Authorization", "Bearer " + token)).andReturn();
            assertThat(result.getResponse().getStatus()).as("%s %s as %s", method, path, principal.get(0)).isIn(403, 404);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(LESSON_B).doesNotContain("Beta School");
        }
    }

    @Test void the_home_and_the_school_routes_serve_the_callers_own_school_only() throws Exception {
        var teacherA = jwt.issue("teacher-a", "teacher@alpha.test", "TEACHER", A).token();
        var managerA = jwt.issue("manager-a", "manager@alpha.test", "MANAGERIAL", A).token();

        var home = json(mvc.perform(get("/me/home").header("Authorization", "Bearer " + teacherA)).andExpect(status().isOk()).andReturn());
        assertThat(home.get("schoolId").asText()).isEqualTo(A);
        assertThat(home.toString()).doesNotContain(LESSON_B).doesNotContain("Beta School").doesNotContain(B);

        // `/school/**` names no school at all, so there is nowhere for a Managerial user to ask for another one.
        var usage = json(mvc.perform(get("/school/usage").header("Authorization", "Bearer " + managerA)).andExpect(status().isOk()).andReturn());
        assertThat(usage.get("schoolId").asText()).isEqualTo(A);
        mvc.perform(get("/school/usage").header("Authorization", "Bearer " + managerA).header(TenantContext.HEADER, B))
                .andExpect(status().isForbidden());

        var teachers = mvc.perform(get("/school/teachers").header("Authorization", "Bearer " + managerA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(teachers).contains("teacher@alpha.test").doesNotContain("teacher@beta.test");

        var classes = mvc.perform(get("/admin/schools/" + A + "/classes").header("Authorization", "Bearer " + teacherA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(classes).contains(A).doesNotContain(B + ":");
    }

    @Test void the_all_lessons_school_filter_narrows_but_never_widens() throws Exception {
        var token = adminToken();
        // The Admin's school column, and the filter behind it.
        var all = adminList(token, null);
        assertThat(all).anySatisfy(l -> {
            if (LESSON_A.equals(l.get("id").asText())) assertThat(l.get("schoolName").asText()).isEqualTo("Alpha Academy");
        });
        assertThat(lessonIdsFiltered(token, null, A)).contains(LESSON_A).doesNotContain(LESSON_B);

        // A teacher of A naming B gets nothing, not B's lessons: her rows were filtered before the parameter was read.
        var teacherA = jwt.issue("teacher-a", "teacher@alpha.test", "TEACHER", A).token();
        var body = mvc.perform(get("/admin/lessons?schoolId=" + B).header("Authorization", "Bearer " + teacherA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(LESSON_B);
        assertThat(mvc.perform(get("/admin/lessons?schoolId=" + A).header("Authorization", "Bearer " + teacherA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains(LESSON_A);
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
                    () -> lessonService.create(new CreateLessonRequest(Curriculum.BRITISH, 1, Subject.MATH, kdate(DATE_A), null, 7, LessonSource.MANUAL, "Managerial", null), null)))
                assertThatThrownBy(write::run).isInstanceOf(ApiException.class)
                        .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("forbidden"));
        });
    }

    /**
     * V7 (D14): a teacher publishes into a <strong>(class, subject) she holds a teaching assignment for</strong>, not
     * into whatever her profile's subjects, curriculum and grades happen to list. The fixture gives `teacher-a` one
     * assignment — British Grade 1 Math — so that combination is hers and every other one is 403, whether it differs
     * by subject, by curriculum or by grade.
     */
    @Test void a_teacher_creates_lessons_only_where_she_holds_an_assignment() {
        as("TEACHER", "teacher-a", A, () -> {
            assertThat(created(Curriculum.BRITISH, 1, Subject.MATH)).isNotNull();                    // hers
            for (Runnable write : List.<Runnable>of(
                    () -> created(Curriculum.BRITISH, 1, Subject.ENGLISH),
                    () -> created(Curriculum.AMERICAN, 1, Subject.MATH),
                    () -> created(Curriculum.BRITISH, 3, Subject.MATH)))
                assertThatThrownBy(write::run).isInstanceOf(ApiException.class).hasMessageContaining("You teach")
                        .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("forbidden"));
        });
        var mine = lessons.findAll().stream().filter(l -> A.equals(l.getSchoolId()) && !LESSON_A.equals(l.getId())).toList();
        assertThat(mine).isNotEmpty().allSatisfy(l -> assertThat(l.getTeacherId()).isEqualTo("teacher-a"));
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

    // ---------------------------------------------------------------- P4.0: the teacher's own routes

    /**
     * Every route P4.0 adds, from the other school's side: teacher A of school A asking about school B's class,
     * child or question gets a 404 (the row is not hers to see) or a 403 (the permission is not hers at all) —
     * never a row of B's. Both features are switched on for both schools first, so a 404 here is isolation rather
     * than a flag that happened to be off.
     */
    static Stream<Arguments> teacherRoutesOfAnotherSchool() {
        return Stream.of(
                Arguments.of("GET", "/teacher/classes/" + CLASS_B + "/calendar?year=2027&month=4"),
                Arguments.of("GET", "/teacher/classes/" + CLASS_B + "/students"),
                // N2.1: the teacher-scoped aliases over the pipeline reach no further than the Admin ones do.
                Arguments.of("GET", "/teacher/lessons/" + LESSON_B),
                Arguments.of("POST", "/teacher/lessons/" + LESSON_B + "/analyze"),
                Arguments.of("POST", "/teacher/lessons/" + LESSON_B + "/retry"),
                Arguments.of("POST", "/teacher/lessons/" + LESSON_B + "/unpublish"),
                Arguments.of("PANEL", "/teacher/lessons/" + LESSON_B + "/parent-panel"),
                Arguments.of("DELETE", "/teacher/lessons/" + LESSON_B),
                Arguments.of("MULTIPART", "/teacher/lessons/" + LESSON_B + "/files"),
                Arguments.of("COPY", "/teacher/lessons/" + LESSON_B + "/copy"),
                Arguments.of("PUBLISH", "/teacher/lessons/" + LESSON_B + "/publish"),
                Arguments.of("QUESTION", "/teacher/questions"),
                Arguments.of("ANNOUNCEMENT", "/teacher/announcements"),
                Arguments.of("GET", "/admin/users/teacher-b/teacher-profile"));
    }

    @ParameterizedTest(name = "{0} {1} of another school is refused for teacher A")
    @MethodSource("teacherRoutesOfAnotherSchool")
    void a_teacher_route_of_another_school_is_refused(String method, String path) throws Exception {
        var adminToken = adminToken();
        for (String school : List.of(A, B)) for (String key : List.of("teacherQuestions", "announcements"))
            mvc.perform(put("/admin/schools/" + school + "/flags/" + key).header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());

        var token = jwt.issue("teacher-a", "teacher@alpha.test", "TEACHER", A).token();
        var result = mvc.perform(requestFor(method, path).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as("%s %s as teacher A", method, path).isIn(403, 404);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(LESSON_B).doesNotContain("Beta School").doesNotContain(CLASS_B);
    }

    /** And the same the other way: teacher B never reads A's class, child or media. */
    @Test void a_teacher_of_b_reads_nothing_of_a() throws Exception {
        var tokenB = jwt.issue("teacher-b", "teacher@beta.test", "TEACHER", B).token();
        var childA = parentPost("/children", "{\"name\":\"Amal\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"SCHLAA\"}").get("id").asText();

        mvc.perform(get("/teacher/classes/" + CLASS_A + "/students").header("Authorization", "Bearer " + tokenB)).andExpect(status().isNotFound());
        mvc.perform(get("/teacher/students/" + childA + "/timeline").header("Authorization", "Bearer " + tokenB)).andExpect(status().isNotFound());
        mvc.perform(get("/teacher/classes/" + CLASS_A + "/calendar?year=2027&month=4").header("Authorization", "Bearer " + tokenB)).andExpect(status().isNotFound());

        // …while her own school's class answers, so the 404s above are isolation and not a broken route
        mvc.perform(get("/teacher/classes/" + CLASS_B + "/students").header("Authorization", "Bearer " + tokenB)).andExpect(status().isOk());
    }

    /**
     * V7, `docs/teacher-flow.md` §2: inside <em>one</em> school, a teacher reaches only the classes she is assigned
     * to. The 404s above are the tenant filter; this is {@link TeacherScope}, and the difference matters — a
     * colleague's class exists and is 403, a stranger's school's does not exist and is 404.
     */
    @Test void a_teacher_is_refused_a_colleagues_class_in_her_own_school() throws Exception {
        String other = A + ":british:2:english";
        user("teacher-a2", A, "teacher2@alpha.test", "TEACHER");
        quest.server.ClassFixtures.section(classes, assignments, other, A, "british", 2, "english", "teacher-a2");
        var tokenA = jwt.issue("teacher-a", "teacher@alpha.test", "TEACHER", A).token();

        var refused = mvc.perform(get("/teacher/classes/" + other + "/students").header("Authorization", "Bearer " + tokenA)).andReturn();
        assertThat(refused.getResponse().getStatus()).as("a colleague's class exists, and is still not hers").isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("2E");
        mvc.perform(get("/teacher/classes/" + other + "/calendar?year=2027&month=4").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
        mvc.perform(get("/teacher/classes/" + CLASS_A + "/students").header("Authorization", "Bearer " + tokenA)).andExpect(status().isOk());
    }

    /** And the Admin cannot hand one teacher a (class, subject) another already holds: 409, naming her. */
    @Test void a_second_teacher_for_the_same_class_and_subject_is_refused_by_name() throws Exception {
        var admin = adminToken();
        users.findById("teacher-a").ifPresent(u -> { u.setDisplayName("Sara Al Harbi"); users.save(u); });
        user("teacher-a3", A, "teacher3@alpha.test", "TEACHER");

        var refused = json(mvc.perform(scoped(put("/admin/teachers/teacher-a3/assignments"), admin, A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignments\":[{\"classId\":\"" + CLASS_A + "\",\"subject\":\"math\"}]}"))
                .andExpect(status().isConflict()).andReturn());
        assertThat(refused.get("message").asText()).isEqualTo("1M · math is taught by Sara Al Harbi");
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

    private List<String> lessonIdsFiltered(String token, String schoolId, String filterSchoolId) throws Exception {
        var out = new java.util.ArrayList<String>();
        json(mvc.perform(scoped(get("/admin/lessons?schoolId=" + filterSchoolId), token, schoolId)).andExpect(status().isOk()).andReturn())
                .forEach(l -> out.add(l.get("id").asText()));
        return out;
    }

    /** The request each row of the parameterised route lists stands for. */
    private static MockHttpServletRequestBuilder requestFor(String method, String path) {
        return switch (method) {
            case "GET" -> get(path);
            case "TEXT" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"A lesson about counting to ten together.\"}");
            case "PANEL" -> put(path).contentType(MediaType.APPLICATION_JSON).content(PANEL);
            case "CLASS" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"english\"}");
            case "QUESTION" -> post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Q\",\"stops\":[],\"classIds\":[\"" + CLASS_B + "\"],\"from\":\"2027-04-01\",\"to\":\"2027-04-30\"}");
            case "ANNOUNCEMENT" -> post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"classId\":\"" + CLASS_B + "\",\"bodyEn\":\"Not mine\"}");
            case "PATCH_CLASS" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"clearTeacher\":true}");
            case "COPY" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{\"classId\":\"" + CLASS_A + "\"}");
            case "PUBLISH" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{\"classIds\":[\"" + CLASS_A + "\"]}");
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
        return lessonService.create(new CreateLessonRequest(curriculum, grade, subject, kdate(DATE_A), null, 7, LessonSource.MANUAL, "Teacher lesson", null), null).getId();
    }

    private static LessonFilter filter() { return new LessonFilter(null, null, null, null, null, null, null); }
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
        quest.server.ClassFixtures.section(classes, assignments, id, schoolId, "british", 1, subject, teacherId);
    }

    private void lesson(String id, String schoolId, LocalDate date) {
        if (lessons.existsById(id)) return;
        var l = new LessonEntity();
        l.setId(id); l.setSchoolId(schoolId); l.setClassId(schoolId + ":british:1:math"); l.setCourseId("british/1"); l.setSubject("math");
        l.setDate(date); l.setStatus("published"); l.setVersion(1); l.setTitle("Lesson of " + schoolId); l.setSource("pdf");
        l.setCreatedAt(Instant.now()); l.setUpdatedAt(Instant.now()); l.setPublishedAt(Instant.now());
        lessons.save(l);
    }

    /**
     * Feature 27's tenant table. A triage is a working note about one family's complaint — what the school thinks it
     * is about, how urgent it is and what it might answer — so it is filtered like every other tenant table: a
     * principal of A finds neither the row nor the complaint it belongs to, and asking for B's own complaint by id
     * answers nothing either.
     */
    @Test void feature_27s_complaint_triage_stays_in_its_school() {
        var complaint = new quest.server.chat.Entities.ChatThreadEntity();
        complaint.setId("iso-complaint-b"); complaint.setSchoolId(B); complaint.setChildId("iso-child-b");
        complaint.setTeacherId("iso-teacher-b"); complaint.setStaffRole("TEACHER"); complaint.setTopic("complaint");
        complaint.setStatus("open"); complaint.setThreadKey("iso-complaint-b"); complaint.setTitle("The bus is late");
        complaint.setCreatedAt(Instant.now());
        chatThreads.save(complaint);
        var row = new quest.server.chat.Entities.ComplaintTriageEntity();
        row.setComplaintId("iso-complaint-b"); row.setSchoolId(B); row.setCategory("transport"); row.setUrgency("high");
        row.setSummaryAr("الحافلة تتأخر"); row.setSummaryEn("The bus is late every morning.");
        row.setReplyAr("شكرًا لتواصلكم."); row.setReplyEn("Thank you for telling us.");
        row.setCreatedAt(Instant.now());
        complaintTriage.save(row);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(complaintTriage.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(complaintTriage.findByComplaintIdIn(List.of("iso-complaint-b"))).as("not A's, whoever she names").isEmpty();
            assertThat(chatThreads.findOneById("iso-complaint-b")).as("nor the complaint it is about").isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(complaintTriage.findByComplaintIdIn(List.of("iso-complaint-b")))
                    .extracting(r -> r.getUrgency()).containsExactly("high");
        } finally {
            tenant.clear();
            complaintTriage.deleteById("iso-complaint-b");
            chatThreads.deleteById("iso-complaint-b");
        }
    }

    /**
     * Feature 32's two tenant tables. A class channel belongs to one school's section and the parents' read marks to
     * one school's families, so a principal of A finds neither the map, nor the channel thread it points at, nor a
     * mark — by `findAll` or by naming B's own section outright.
     */
    @Test void feature_32s_class_channel_tables_stay_in_their_school() {
        var channel = new quest.server.chat.Entities.ChatThreadEntity();
        channel.setId("iso-channel-b"); channel.setSchoolId(B); channel.setTeacherId(CLASS_B); channel.setClassId(CLASS_B);
        channel.setKind("class_channel"); channel.setStaffRole("TEACHER"); channel.setTopic("question");
        channel.setStatus("open"); channel.setCreatedAt(Instant.now());
        chatThreads.save(channel);
        var map = new quest.server.chat.Entities.ClassChannelEntity();
        map.setId("iso-channel-map-b"); map.setSchoolId(B); map.setClassId(CLASS_B); map.setThreadId("iso-channel-b");
        map.setCreatedAt(Instant.now());
        classChannels.save(map);
        var mark = new quest.server.chat.Entities.ChannelReadEntity();
        mark.setId("iso-channel-read-b"); mark.setSchoolId(B); mark.setThreadId("iso-channel-b");
        mark.setParentId("iso-parent-b"); mark.setLastReadMessageId("iso-post-b"); mark.setReadAt(Instant.now());
        channelReads.save(mark);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(classChannels.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(classChannels.findOneByClassId(CLASS_B)).as("not A's, whichever section she names").isEmpty();
            assertThat(chatThreads.findChannelOf(CLASS_B)).as("nor the channel thread itself").isEmpty();
            assertThat(channelReads.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(channelReads.findOne("iso-channel-b", "iso-parent-b")).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(classChannels.findOneByClassId(CLASS_B)).map(r -> r.getThreadId()).contains("iso-channel-b");
            assertThat(chatThreads.findChannelOf(CLASS_B)).isPresent();
            assertThat(channelReads.findOne("iso-channel-b", "iso-parent-b")).isPresent();
        } finally {
            tenant.clear();
            channelReads.deleteById("iso-channel-read-b");
            classChannels.deleteById("iso-channel-map-b");
            chatThreads.deleteById("iso-channel-b");
        }
    }

    /**
     * Feature 33's three tenant tables. A round is one school's decision, what a teacher wrote about a child is that
     * school's business, and the reminders it has sent are its own: a principal of A reads none of B's rows, and
     * naming B's own round, teacher or child outright does not help her — the filter is on the query, not on the
     * argument.
     */
    @Test void feature_33s_child_evaluation_tables_stay_in_their_school() {
        var round = new quest.server.evaluations.Entities.EvaluationRoundEntity();
        round.setId("iso-round-b"); round.setSchoolId(B); round.setNameEn("B's round"); round.setNameAr("دورة ب");
        round.setOpensOn(LocalDate.of(2026, 11, 1)); round.setDueAt(Instant.now()); round.setRemindHoursBefore(24);
        round.setCreatedAt(Instant.now()); round.setUpdatedAt(Instant.now());
        evaluationRounds.save(round);
        var evaluation = new quest.server.evaluations.Entities.ChildEvaluationEntity();
        evaluation.setId("iso-evaluation-b"); evaluation.setSchoolId(B); evaluation.setRoundId("iso-round-b");
        evaluation.setTeacherId("teacher-b"); evaluation.setClassId(CLASS_B); evaluation.setSubject("math");
        evaluation.setChildId("iso-child-b"); evaluation.setState("APPROVED");
        evaluation.setComment("B's own words"); evaluation.setSubmittedAt(Instant.now());
        evaluation.setCreatedAt(Instant.now()); evaluation.setUpdatedAt(Instant.now());
        childEvaluations.save(evaluation);
        var reminder = new quest.server.evaluations.Entities.EvaluationReminderEntity();
        reminder.setId("iso-evaluation-reminder-b"); reminder.setSchoolId(B); reminder.setRoundId("iso-round-b");
        reminder.setTeacherId("teacher-b"); reminder.setSentAt(Instant.now());
        evaluationReminders.save(reminder);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(evaluationRounds.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(evaluationRounds.findOneBySchoolIdAndId(B, "iso-round-b")).as("nor by naming B outright").isEmpty();
            assertThat(evaluationRounds.findBySchoolIdOrderByDueAtDesc(B)).isEmpty();
            assertThat(childEvaluations.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(childEvaluations.findBySchoolIdAndRoundId(B, "iso-round-b")).isEmpty();
            assertThat(childEvaluations.findOneBySchoolIdAndId(B, "iso-evaluation-b")).isEmpty();
            assertThat(childEvaluations.findOneBySchoolIdAndRoundIdAndTeacherIdAndClassIdAndSubjectAndChildId(
                    B, "iso-round-b", "teacher-b", CLASS_B, "math", "iso-child-b")).isEmpty();
            assertThat(evaluationReminders.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(evaluationReminders.findBySchoolIdAndRoundId(B, "iso-round-b")).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(evaluationRounds.findOneBySchoolIdAndId(B, "iso-round-b")).isPresent();
            assertThat(childEvaluations.findBySchoolIdAndRoundId(B, "iso-round-b"))
                    .extracting(quest.server.evaluations.Entities.ChildEvaluationEntity::getId).containsExactly("iso-evaluation-b");
            assertThat(evaluationReminders.findBySchoolIdAndRoundId(B, "iso-round-b")).hasSize(1);
        } finally {
            tenant.clear();
            evaluationReminders.deleteById("iso-evaluation-reminder-b");
            childEvaluations.deleteById("iso-evaluation-b");
            evaluationRounds.deleteById("iso-round-b");
        }
    }

    /**
     * Feature 34's two settings tables. Which kinds of leave a school offers, when it asks for a document and how
     * many days a year each category gets are that school's own policy: a principal of A reads none of B's rows, and
     * naming B's school id, type or category outright does not help her.
     */
    @Test void feature_34s_leave_settings_tables_stay_in_their_school() {
        var type = new quest.server.staffattendance.Entities.LeaveTypeEntity();
        type.setId("iso-leave-type-b"); type.setSchoolId(B); type.setLeaveType("SICK");
        type.setEnabled(true); type.setDocumentAfterDays(3); type.setUpdatedAt(Instant.now());
        leaveTypes.save(type);
        var balance = new quest.server.staffattendance.Entities.LeaveBalanceEntity();
        balance.setId("iso-leave-balance-b"); balance.setSchoolId(B); balance.setLeaveType("ANNUAL");
        balance.setCategory("TEACHER"); balance.setYearlyDays(21); balance.setUpdatedAt(Instant.now());
        leaveBalances.save(balance);
        try {
            tenant.set("MANAGERIAL", A, null);
            assertThat(leaveTypes.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(leaveTypes.findBySchoolIdOrderByLeaveTypeAsc(B)).as("nor by naming B outright").isEmpty();
            assertThat(leaveTypes.findOneBySchoolIdAndLeaveType(B, "SICK")).isEmpty();
            assertThat(leaveBalances.findAll()).noneMatch(r -> B.equals(r.getSchoolId()));
            assertThat(leaveBalances.findBySchoolIdOrderByLeaveTypeAscCategoryAsc(B)).isEmpty();
            assertThat(leaveBalances.findOneBySchoolIdAndLeaveTypeAndCategory(B, "ANNUAL", "TEACHER")).isEmpty();
            tenant.set("MANAGERIAL", B, null);
            assertThat(leaveTypes.findOneBySchoolIdAndLeaveType(B, "SICK"))
                    .map(quest.server.staffattendance.Entities.LeaveTypeEntity::getDocumentAfterDays).contains(3);
            assertThat(leaveBalances.findOneBySchoolIdAndLeaveTypeAndCategory(B, "ANNUAL", "TEACHER"))
                    .map(quest.server.staffattendance.Entities.LeaveBalanceEntity::getYearlyDays).contains(21);
        } finally {
            tenant.clear();
            leaveBalances.deleteById("iso-leave-balance-b");
            leaveTypes.deleteById("iso-leave-type-b");
        }
    }
}
