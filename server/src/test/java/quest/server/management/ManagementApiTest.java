package quest.server.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ClassFixtures;
import quest.server.exams.ExamLevels;
import quest.server.exams.ExamSettingsRepository;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingTestSupport;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.StaffScopeRepository;

/**
 * RM1 on the shape DR5 describes: <strong>two managers, one per department</strong>. Nour runs British, Sami runs
 * American, and the school is the smallest one in which "her department and nobody else's" can be told apart at all —
 * Maya teaches maths in two British grades, Rami english in one American grade, Lina coordinates maths/British and Omar
 * english/American.
 *
 * <p>What it proves, in DR5's order: her `/management/me` is her department and its five numbers; the three people
 * screens answer her half of the school and not the other manager's; the statistics count the published lesson, the
 * register the teacher took and the exam somebody sat; a class, a lesson and a child of the other department are 403 and
 * a row of no school is 404; her numbers are the teacher's numbers byte for byte; every write is refused; and the Admin
 * mints a manager and moves her department.
 */
class ManagementApiTest extends GradingTestSupport {
    private static final String P = "mgmt-";
    private static final String SCHOOL = P + "school", CODE = "MGMT01";
    private static final String MAYA = P + "maya", RAMI = P + "rami";
    private static final String NOUR = P + "nour", SAMI = P + "sami", LINA = P + "lina", OMAR = P + "omar";
    private static final String BRITISH_A = P + "1a-british", BRITISH_B = P + "2a-british", AMERICAN_A = P + "1a-american";
    private static final String LESSON = P + "lesson", DRAFT = P + "draft", EXAM = P + "exam", THEIR_LESSON = P + "their-lesson";

    @Override public String prefix() { return P; }

    @Autowired StaffScopeRepository staffScopes;
    @Autowired ExamSettingsRepository examSettings;
    @Autowired quest.server.exams.ExamAttemptRepository examSittings;

    private String adminToken, teacher, nour, sami;
    private ClassEntity britishA, britishB, americanA;
    private String kidBritish, kidAmerican;
    private final LocalDate day = LocalDate.now().minusDays(2);

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Two Departments Academy", CODE);
        teacher(MAYA, SCHOOL, "Ms Maya"); teacher(RAMI, SCHOOL, "Mr Rami");
        staff(NOUR, "Nour", "MANAGERIAL"); staff(SAMI, "Sami", "MANAGERIAL");
        staff(LINA, "Lina", "COORDINATOR"); staff(OMAR, "Omar", "COORDINATOR");
        scopeRow(P + "dept-british", NOUR, null, "british");
        scopeRow(P + "dept-american", SAMI, null, "american");
        scopeRow(P + "scope-math", LINA, "math", "british");
        scopeRow(P + "scope-english", OMAR, "english", "american");

        britishA = klass(BRITISH_A, SCHOOL, MAYA, "1A British");
        // Grade 2 rather than a second grade-1 section: `classes` is unique on (school, curriculum, grade, subject, teacher).
        britishB = ClassFixtures.section(classes, assignments, BRITISH_B, SCHOOL, "british", 2, "math", MAYA);
        britishB.setName("2A British"); classes.save(britishB);
        americanA = ClassFixtures.section(classes, assignments, AMERICAN_A, SCHOOL, "american", 1, "english", RAMI);
        americanA.setName("1A American"); classes.save(americanA);

        adminToken = adminToken();
        enableGrading(adminToken, SCHOOL);
        setFlag(adminToken, SCHOOL, FlagKeys.EXAMS, true);
        teacher = token(MAYA, "TEACHER", SCHOOL);
        nour = token(NOUR, "MANAGERIAL", SCHOOL); sami = token(SAMI, "MANAGERIAL", SCHOOL);

        kidBritish = child("Hana", CODE, britishA);
        kidAmerican = child("Yousef", CODE, americanA);

        lesson(LESSON, SCHOOL, britishA, day);
        playTheSample(kidBritish, LESSON);
        var draft = lesson(DRAFT, SCHOOL, britishB, day);
        draft.setStatus("draft"); draft.setPublishedAt(null); lessons.save(draft);
        examLesson(EXAM, britishA, "math");
        playTheSample(kidBritish, EXAM);
        var theirs = lesson(THEIR_LESSON, SCHOOL, americanA, day);
        theirs.setSubject("english"); lessons.save(theirs);
    }

    @AfterEach void clean() {
        examSittings.deleteAll(examSittings.findAll().stream().filter(a -> a.getSchoolId().startsWith(P)).toList());
        examSettings.deleteAll(examSettings.findAll().stream().filter(e -> e.getSchoolId().startsWith(P)).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getId().startsWith(P)).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- her department, and only hers

    @Test void the_british_manager_sees_mayas_grades_lina_and_nothing_american() throws Exception {
        var me = json(mvc.perform(as(get("/management/me"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("displayName").asText()).isEqualTo("Nour");
        assertThat(ids(me.get("departments"))).containsExactly("british");
        assertThat(me.get("grades").asInt()).isEqualTo(2);
        assertThat(me.get("sections").asInt()).isEqualTo(2);
        assertThat(me.get("teachers").asInt()).isOne();
        assertThat(me.get("coordinators").asInt()).isOne();
        assertThat(me.get("children").asInt()).isOne();

        // DR5 in the dashboard's own `/me`: `assignments`' sibling, so the Management area needs no second request.
        var account = json(mvc.perform(as(get("/me"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(ids(account.get("departments"))).containsExactly("british");
        assertThat(account.get("assignments").isNull()).as("she is not assignment-scoped").isTrue();

        assertThat(field(get("/management/teachers"), nour, "userId")).containsExactly(MAYA);
        assertThat(field(get("/management/coordinators"), nour, "userId")).containsExactly(LINA);
        var grades = json(mvc.perform(as(get("/management/classes"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(grades).hasSize(2);
        assertThat(field(get("/management/classes"), nour, "grade")).containsExactly("1", "2");
        assertThat(grades.get(0).get("classes").get(0).get("className").asText()).isEqualTo("1A British");
        assertThat(grades.get(0).get("children").asInt()).isOne();
    }

    @Test void the_american_manager_is_the_mirror_image() throws Exception {
        var me = json(mvc.perform(as(get("/management/me"), sami)).andExpect(status().isOk()).andReturn());
        assertThat(ids(me.get("departments"))).containsExactly("american");
        assertThat(me.get("sections").asInt()).isOne();

        assertThat(field(get("/management/teachers"), sami, "userId")).containsExactly(RAMI);
        assertThat(field(get("/management/coordinators"), sami, "userId")).containsExactly(OMAR);
        assertThat(field(get("/management/classes"), sami, "curriculum")).containsExactly("american");
        assertThat(field(get("/management/lessons"), sami, "id")).containsExactly(THEIR_LESSON);
    }

    @Test void a_coordinator_of_both_tracks_reports_to_both_managers() throws Exception {
        scopeRow(P + "scope-science", LINA, "science", null);
        assertThat(field(get("/management/coordinators"), nour, "userId")).containsExactly(LINA);
        assertThat(field(get("/management/coordinators"), sami, "userId")).containsExactlyInAnyOrder(LINA, OMAR);
    }

    @Test void her_lesson_list_and_calendar_are_her_department() throws Exception {
        assertThat(field(get("/management/lessons"), nour, "id")).containsExactlyInAnyOrder(LESSON, DRAFT, EXAM);
        assertThat(field(get("/management/lessons?status=draft"), nour, "id")).containsExactly(DRAFT);
        assertThat(field(get("/management/lessons?classId=" + BRITISH_B), nour, "id")).containsExactly(DRAFT);
        mvc.perform(as(get("/management/lessons?status=nonsense"), nour)).andExpect(status().isBadRequest());

        var calendar = json(mvc.perform(as(get("/management/calendar?from=" + day + "&to=" + day), nour))
                .andExpect(status().isOk()).andReturn());
        assertThat(calendar.get("days")).hasSize(1);
        assertThat(ids(calendar.get("days").get(0).get("lessons"), "className")).containsExactly("1A British", "2A British");
        mvc.perform(as(get("/management/calendar?from=" + day + "&to=" + day.minusDays(1)), nour)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- DR5's statistics

    @Test void the_statistics_count_the_lesson_the_register_and_the_exam() throws Exception {
        mvc.perform(as(post("/teacher/classes/" + BRITISH_A + "/attendance").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + day + "\",\"items\":[{\"childId\":\"" + kidBritish + "\",\"status\":\"LATE\"}]}"), teacher))
                .andExpect(status().isOk());

        var body = json(mvc.perform(as(get("/management/stats?from=" + day.minusDays(1) + "&to=" + day.plusDays(1)), nour))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("grades")).hasSize(2);
        var first = body.get("grades").get(0);
        assertThat(first.get("curriculum").asText()).isEqualTo("british");
        assertThat(first.get("grade").asInt()).isOne();
        assertThat(first.get("children").asInt()).isOne();
        assertThat(first.get("sections").asInt()).isOne();
        // The register: one LATE row of one marked row, and `(present + late) / marked` is the teacher's own formula.
        assertThat(first.get("attendanceRate").asDouble()).isEqualTo(100.0);
        // The published homework and the published exam; the draft in grade 2 counts in neither column.
        assertThat(first.get("lessonsPublished").asInt()).isEqualTo(2);
        assertThat(first.get("lessonsPlayed").asInt()).isEqualTo(2);
        assertThat(first.get("exams").asInt()).isOne();
        assertThat(first.get("examAverage").asInt()).isPositive();
        assertThat(first.get("examPassRate").asInt()).isBetween(0, 100);
        assertThat(first.get("quietTeachers")).as("Maya published in grade 1").isEmpty();

        // Grade 2 holds the draft only: nothing published, nothing marked, and Maya is quiet in it.
        var second = body.get("grades").get(1);
        assertThat(second.get("grade").asInt()).isEqualTo(2);
        assertThat(second.get("lessonsPublished").asInt()).isZero();
        assertThat(second.get("attendanceRate").isNull()).as("an empty register is no answer, not a perfect one").isTrue();
        assertThat(ids(second.get("quietTeachers"), "userId")).containsExactly(MAYA);

        // The department's own total: the grades added up, with no grade and no track of its own.
        var total = body.get("total");
        assertThat(total.get("grade").asInt()).isZero();
        assertThat(total.get("curriculum").isNull()).isTrue();
        assertThat(total.get("sections").asInt()).isEqualTo(2);
        assertThat(total.get("lessonsPublished").asInt()).isEqualTo(2);
        assertThat(total.get("quietTeachers")).as("she published somewhere in the department").isEmpty();

        // The other department's numbers are somebody else's: Sami's grade row never mentions Maya's sections.
        assertThat(ids(json(mvc.perform(as(get("/management/stats"), sami)).andExpect(status().isOk()).andReturn())
                .get("grades"), "curriculum")).containsExactly("american");
        mvc.perform(as(get("/management/stats?from=2026-01-01&to=2027-01-01"), nour)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- parity, and the other department

    @Test void her_numbers_are_the_teachers_numbers() throws Exception {
        assertSameBody("/teacher/classes/" + BRITISH_A + "/gradebook", "/management/classes/" + BRITISH_A + "/results");
        assertSameBody("/teacher/classes/" + BRITISH_A + "/exams", "/management/classes/" + BRITISH_A + "/exams");
        assertSameBody("/teacher/lessons/" + LESSON + "/results", "/management/lessons/" + LESSON + "/results");
        assertSameBody("/teacher/exams/" + EXAM + "/results", "/management/exams/" + EXAM + "/results");
        assertSameBody("/teacher/children/" + kidBritish, "/management/children/" + kidBritish);
        assertSameBody("/teacher/lessons/" + LESSON, "/management/lessons/" + LESSON);
        assertSameBody("/teacher/lessons/" + LESSON + "/status", "/management/lessons/" + LESSON + "/status");

        var register = json(mvc.perform(as(get("/management/classes/" + BRITISH_A + "/attendance?from=" + day + "&to=" + day), nour))
                .andExpect(status().isOk()).andReturn());
        assertThat(register).hasSize(1);
        assertThat(register.get(0)).isEqualTo(json(mvc.perform(as(get("/teacher/classes/" + BRITISH_A + "/attendance?date=" + day), teacher))
                .andExpect(status().isOk()).andReturn()));
    }

    @Test void a_class_a_lesson_and_a_child_of_the_other_department_are_refused() throws Exception {
        for (var path : List.of("/management/classes/" + AMERICAN_A + "/attendance",
                "/management/classes/" + AMERICAN_A + "/results", "/management/classes/" + AMERICAN_A + "/exams",
                "/management/children/" + kidAmerican, "/management/lessons/" + THEIR_LESSON,
                "/management/lessons/" + THEIR_LESSON + "/status", "/management/lessons/" + THEIR_LESSON + "/results",
                "/management/lessons?classId=" + AMERICAN_A))
            mvc.perform(as(get(path), nour)).andExpect(status().isForbidden());
        // A row of no school at all is a 404: the refusal never doubles as confirmation that an id is real.
        for (var path : List.of("/management/lessons/no-such-lesson", "/management/children/no-such-child",
                "/management/classes/no-such-class/results"))
            mvc.perform(as(get(path), nour)).andExpect(status().isNotFound());
        // And the mirror: the American manager is refused Maya's British section.
        mvc.perform(as(get("/management/classes/" + BRITISH_A + "/results"), sami)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- read-only, and only her own area

    @Test void every_write_is_refused_and_the_other_roles_cannot_reach_the_area() throws Exception {
        // The bodies are valid ones on purpose: `/admin/**` and `/teacher/**` let a MANAGERIAL caller knock (her School
        // and Teachers screens live there), so argument resolution runs before the permission and an empty body would
        // answer 400 rather than the 403 this test is about.
        var refused = List.<MockHttpServletRequestBuilder>of(
                patch("/teacher/lessons/" + LESSON).contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + day + "\"}"),
                delete("/teacher/lessons/" + LESSON),
                post("/admin/managers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Hers\",\"email\":\"hers@test.com\",\"curriculum\":\"british\"}"),
                put("/admin/managers/" + NOUR + "/scopes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"curricula\":[\"american\"]}"),
                get("/admin/managers"), get("/admin/coordinators"), get("/coordinator/me"));
        for (var request : refused) mvc.perform(as(request, nour)).andExpect(status().isForbidden());
        assertThat(ids(json(mvc.perform(as(get("/management/me"), nour)).andExpect(status().isOk()).andReturn()).get("departments")))
                .as("nothing above widened her own department").containsExactly("british");

        for (String role : List.of("TEACHER", "COORDINATOR"))
            mvc.perform(as(get("/management/me"), token(P + "who-" + role, role, SCHOOL))).andExpect(status().isForbidden());
        mvc.perform(get("/management/me")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- the Admin's side

    @Test void the_admin_creates_a_manager_and_moves_her_department() throws Exception {
        var created = json(mvc.perform(scoped(post("/admin/managers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Huda Manager\",\"email\":\"Huda.Manager@test.com\",\"curriculum\":\"american\"}")))
                .andExpect(status().isCreated()).andReturn());
        String userId = created.get("manager").get("userId").asText();
        assertThat(created.get("temporaryPassword").asText()).isNotBlank();
        assertThat(created.get("manager").get("email").asText()).isEqualTo("huda.manager@test.com");
        assertThat(ids(created.get("manager").get("departments"))).containsExactly("american");

        assertThat(field(scoped(get("/admin/managers")), "userId")).contains(userId, NOUR, SAMI);

        // Her new department is what she reaches, without a second request to build it.
        assertThat(ids(json(mvc.perform(as(get("/management/me"), token(userId, "MANAGERIAL", SCHOOL)))
                .andExpect(status().isOk()).andReturn()).get("departments"))).containsExactly("american");

        var changed = json(mvc.perform(scoped(put("/admin/managers/" + userId + "/scopes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"curricula\":[\"british\",\"american\"]}")))
                .andExpect(status().isOk()).andReturn());
        assertThat(ids(changed.get("departments"))).containsExactlyInAnyOrder("american", "british");

        // Refused, and nothing written: a track named twice, a track the contract has no name for, and an empty set.
        for (String body : List.of("{\"curricula\":[\"british\",\"british\"]}", "{\"curricula\":[\"martian\"]}",
                "{\"curricula\":[]}"))
            mvc.perform(scoped(put("/admin/managers/" + userId + "/scopes").contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        JsonNode hers = null;
        for (var row : json(mvc.perform(scoped(get("/admin/managers"))).andExpect(status().isOk()).andReturn()))
            if (userId.equals(row.get("userId").asText())) hers = row;
        assertThat(ids(java.util.Objects.requireNonNull(hers).get("departments"))).containsExactlyInAnyOrder("american", "british");

        // A teacher is not a manager, and an address that already has an account is a conflict.
        mvc.perform(scoped(put("/admin/managers/" + MAYA + "/scopes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curricula\":[\"british\"]}"))).andExpect(status().isNotFound());
        mvc.perform(scoped(post("/admin/managers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Again\",\"email\":\"huda.manager@test.com\",\"curriculum\":\"british\"}")))
                .andExpect(status().isConflict());

        staffScopes.deleteAll(staffScopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(SCHOOL, userId));
        users.deleteById(userId);
    }

    // ---------------------------------------------------------------- fixture helpers

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder) throws Exception {
        return as(builder, adminToken).header(quest.server.tenancy.TenantContext.HEADER, SCHOOL);
    }

    /**
     * MG1 addendum: the manager's School usage screen read `GET /school/usage`, which is the <em>whole</em> school —
     * a British manager was shown the American department's plays and Rami beside her own teacher.
     * `GET /management/usage` is the same shape scoped to her sections and her teachers.
     */
    @Test void her_school_usage_counts_her_department_and_not_the_other_one() throws Exception {
        // The window is named rather than defaulted: the default ends on the injected clock's *yesterday*, which the
        // open `backend/usage-window-boundary` branch is fixing — this route is not the place to decide that.
        String window = "?from=" + day.minusDays(2) + "&to=" + LocalDate.now().plusDays(1);
        var hers = json(mvc.perform(as(get("/management/usage" + window), nour)).andExpect(status().isOk()).andReturn());
        assertThat(hers.get("schoolId").asText()).isEqualTo(SCHOOL);
        assertThat(hers.get("children").asInt()).as("Hana sits in a British section; Yousef is Sami's").isOne();
        assertThat(ids(hers.get("teacherConsistency"), "teacherId")).containsExactly(MAYA);
        assertThat(total(hers.get("lessonsPublishedPerWeek")))
                .as("the British homework and exam; the grade 2 draft and Rami's lesson are neither").isEqualTo(2);
        assertThat(total(hers.get("playsPerDay"))).as("Hana played the British lesson").isPositive();
        var his = json(mvc.perform(as(get("/management/usage" + window), sami)).andExpect(status().isOk()).andReturn());
        assertThat(his.get("children").asInt()).isOne();
        assertThat(ids(his.get("teacherConsistency"), "teacherId")).containsExactly(RAMI);
        assertThat(total(his.get("lessonsPublishedPerWeek"))).as("Rami published once").isOne();
        assertThat(total(his.get("playsPerDay"))).as("nobody American has played").isZero();
        assertThat(hers.get("activeFamilies").asInt()).as("one British family answered in the window").isOne();
        assertThat(his.get("activeFamilies").asInt()).as("no American family has").isZero();

        // A window that is not one is refused by the very code `/school/usage` uses.
        mvc.perform(as(get("/management/usage?from=" + day + "&to=" + day.minusDays(30)), nour)).andExpect(status().isBadRequest());
    }

    /** The sum of a `DayCount` / `WeekCount` series — the screen draws the bars, the test adds them up. */
    private static int total(JsonNode series) {
        int sum = 0;
        for (var row : series) sum += row.get("count").asInt();
        return sum;
    }

    private List<String> ids(JsonNode rows) { return ids(rows, null); }

    private List<String> ids(JsonNode rows, String field) {
        var out = new java.util.ArrayList<String>();
        rows.forEach(row -> out.add((field == null ? row : row.get(field)).asText()));
        return out;
    }

    private List<String> field(MockHttpServletRequestBuilder request, String token, String name) throws Exception {
        return ids(json(mvc.perform(as(request, token)).andExpect(status().isOk()).andReturn()), name);
    }

    private List<String> field(MockHttpServletRequestBuilder request, String name) throws Exception {
        return ids(json(mvc.perform(request).andExpect(status().isOk()).andReturn()), name);
    }

    /** The two routes answer the same JSON — the whole body, not a field of it. */
    private void assertSameBody(String teacherPath, String managerPath) throws Exception {
        JsonNode hers = json(mvc.perform(as(get(teacherPath), teacher)).andExpect(status().isOk()).andReturn());
        JsonNode theirs = json(mvc.perform(as(get(managerPath), nour)).andExpect(status().isOk()).andReturn());
        assertThat(theirs).as("%s must answer exactly what %s answers", managerPath, teacherPath).isEqualTo(hers);
    }

    /** A staff account of one of the two scoped roles, with no teacher profile behind it. */
    private void staff(String id, String displayName, String role) {
        var u = users.findById(id).orElseGet(quest.server.auth.Entities.UserEntity::new);
        u.setId(id); u.setSchoolId(SCHOOL); u.setEmail(id + "@seed.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    /** One `staff_scopes` row: a manager's is a department (`subject` null), a coordinator's a subject. */
    private void scopeRow(String id, String userId, String subject, String curriculum) {
        var row = staffScopes.findById(id).orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(id); row.setSchoolId(SCHOOL); row.setUserId(userId);
        row.setSubject(subject); row.setCurriculum(curriculum);
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
    }

    /** A closed exam on a section: the hand-scored lesson, typed `exam`, with its `exam_settings` row beside it. */
    private void examLesson(String id, ClassEntity section, String subject) {
        var l = lesson(id, SCHOOL, section, day);
        l.setType("exam"); l.setSubject(subject); lessons.save(l);
        var now = Instant.now();
        var row = examSettings.findById(id).orElseGet(quest.server.exams.Entities.ExamSettingsEntity::new);
        row.setLessonId(id); row.setSchoolId(SCHOOL); row.setLevel(ExamLevels.ONE);
        row.setReleaseMode(ExamLevels.MANUAL);
        row.setOpensAt(now.minus(120, ChronoUnit.MINUTES)); row.setClosesAt(now.minus(60, ChronoUnit.MINUTES));
        row.setCreatedAt(now); row.setUpdatedAt(now);
        examSettings.save(row);
    }
}
