package quest.server.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ClassFixtures;
import quest.server.exams.ExamLevels;
import quest.server.exams.ExamSettingsRepository;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingTestSupport;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.StaffScopeRepository;

/**
 * R3 (DR2): <strong>the coordinator's numbers are the teacher's numbers</strong>. Every parity test below asks the
 * same question twice — once as the teacher who owns the section and once as the coordinator who supervises it — and
 * asserts the two bodies are the same JSON. That is the promise R6 draws on: the dashboard reuses the teacher's
 * attendance, gradebook, exam and child components in read mode, so a second scoring pass on the server would show up
 * as two different grids for one class.
 *
 * <p>The fixture is `GradingTestSupport`'s hand-scored lesson, in Maya's math/British section, with Rami's
 * english/American section beside it as the thing Lina may not read: her scope is `(math, british)`, so the second
 * section fails both halves of the rule and every refusal below is a 403 rather than an empty body.
 */
class CoordinatorReadsApiTest extends GradingTestSupport {
    private static final String P = "coord-reads-";
    private static final String SCHOOL = P + "school", CODE = "CRDRD1";
    private static final String MAYA = P + "maya", RAMI = P + "rami", LINA = P + "lina";
    private static final String MINE = P + "1a-british", THEIRS = P + "1a-american";
    private static final String LESSON = P + "lesson", EXAM = P + "exam", THEIR_EXAM = P + "their-exam";

    @Override public String prefix() { return P; }

    @Autowired StaffScopeRepository staffScopes;
    @Autowired ExamSettingsRepository examSettings;
    @Autowired quest.server.exams.ExamAttemptRepository examSittings;

    private String adminToken, teacher, coordinator, kidMine, kidTheirs;
    private ClassEntity mine, theirs;
    private final LocalDate day = LocalDate.now().minusDays(2);

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Coordinator Reads Academy", CODE);
        teacher(MAYA, SCHOOL, "Ms Maya"); teacher(RAMI, SCHOOL, "Mr Rami");
        staff(LINA, "Ms Lina");
        scopeRow(P + "scope-math", LINA, "math", "british");

        mine = klass(MINE, SCHOOL, MAYA, "1A British");
        theirs = ClassFixtures.section(classes, assignments, THEIRS, SCHOOL, "american", 1, "english", RAMI);
        theirs.setName("1A American"); classes.save(theirs);

        adminToken = adminToken();
        enableGrading(adminToken, SCHOOL);
        setFlag(adminToken, SCHOOL, FlagKeys.EXAMS, true);
        teacher = token(MAYA, "TEACHER", SCHOOL);
        coordinator = token(LINA, "COORDINATOR", SCHOOL);

        kidMine = child("Hana", CODE, mine);
        kidTheirs = child("Yousef", CODE, theirs);

        lesson(LESSON, SCHOOL, mine, day);
        playTheSample(kidMine, LESSON);
        examLesson(EXAM, mine);
        examLesson(THEIR_EXAM, theirs);
    }

    @AfterEach void clean() {
        examSittings.deleteAll(examSittings.findAll().stream().filter(a -> a.getSchoolId().startsWith(P)).toList());
        examSettings.deleteAll(examSettings.findAll().stream().filter(e -> e.getSchoolId().startsWith(P)).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- parity with the teacher

    @Test void the_register_she_reads_is_the_register_the_teacher_took() throws Exception {
        mvc.perform(as(post("/teacher/classes/" + MINE + "/attendance").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + day + "\",\"items\":[{\"childId\":\"" + kidMine + "\",\"status\":\"LATE\",\"notes\":\"bus\"}]}"), teacher))
                .andExpect(status().isOk());

        var hers = json(mvc.perform(as(get("/teacher/classes/" + MINE + "/attendance?date=" + day), teacher))
                .andExpect(status().isOk()).andReturn());
        var window = json(mvc.perform(as(get("/coordinator/classes/" + MINE + "/attendance?from=" + day + "&to=" + day.plusDays(1)),
                coordinator)).andExpect(status().isOk()).andReturn());

        assertThat(window).hasSize(2);
        assertThat(window.get(0)).as("day for day, the teacher's own body").isEqualTo(hers);
        assertThat(window.get(0).get("lateCount").asInt()).isOne();
        assertThat(window.get(1).get("date").asText()).isEqualTo(day.plusDays(1).toString());
        assertThat(window.get(1).get("students").get(0).get("status").asText()).isEqualTo("NOT_MARKED");

        // Both bounds absent is the week ending today, so the register she just took is the last day of it.
        var thisWeek = json(mvc.perform(as(get("/coordinator/classes/" + MINE + "/attendance"), coordinator))
                .andExpect(status().isOk()).andReturn());
        assertThat(thisWeek).hasSize(7);
        assertThat(thisWeek.get(6).get("date").asText()).isEqualTo(LocalDate.now().toString());
    }

    @Test void the_gradebook_the_results_and_the_child_page_are_the_teachers_own_bodies() throws Exception {
        String window = "?from=" + day.minusDays(1) + "&to=" + day.plusDays(1);
        assertSameBody("/teacher/classes/" + MINE + "/gradebook" + window, "/coordinator/classes/" + MINE + "/results" + window);
        assertSameBody("/teacher/lessons/" + LESSON + "/results", "/coordinator/lessons/" + LESSON + "/results");
        assertSameBody("/teacher/children/" + kidMine, "/coordinator/children/" + kidMine);

        // Not an empty grid dressed up as parity: the hand-scored sample is in both of them.
        var book = json(mvc.perform(as(get("/coordinator/classes/" + MINE + "/results" + window), coordinator)).andReturn());
        assertThat(book.get("subject").asText()).isEqualTo("math");
        assertThat(book.get("needsMarking").asInt()).as("the sample's retell is still waiting for the teacher").isOne();
        assertThat(book.get("lessons")).anySatisfy(column -> {
            assertThat(column.get("lessonId").asText()).isEqualTo(LESSON);
            assertThat(column.get("classAverage").asInt()).isEqualTo(50);
        });
        assertThat(json(mvc.perform(as(get("/coordinator/children/" + kidMine), coordinator)).andReturn())
                .get("classId").asText()).isEqualTo(MINE);
    }

    @Test void the_exams_tab_and_the_exam_results_are_the_teachers_own_bodies() throws Exception {
        assertSameBody("/teacher/classes/" + MINE + "/exams", "/coordinator/classes/" + MINE + "/exams");
        assertSameBody("/teacher/exams/" + EXAM + "/results", "/coordinator/exams/" + EXAM + "/results");

        var rows = json(mvc.perform(as(get("/coordinator/classes/" + MINE + "/exams"), coordinator)).andReturn());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("examId").asText()).isEqualTo(EXAM);
        assertThat(rows.get(0).get("state").asText()).isEqualTo(ExamLevels.CLOSED);
    }

    // ---------------------------------------------------------------- what she may not read

    @Test void another_subjects_section_child_and_exam_are_refused() throws Exception {
        for (String path : java.util.List.of(
                "/coordinator/classes/" + THEIRS + "/attendance",
                "/coordinator/classes/" + THEIRS + "/results",
                "/coordinator/classes/" + THEIRS + "/exams",
                "/coordinator/children/" + kidTheirs,
                "/coordinator/exams/" + THEIR_EXAM + "/results"))
            mvc.perform(as(get(path), coordinator)).andExpect(status().isForbidden());

        // A row of no school at all is a 404: a refusal never doubles as confirmation that an id is real.
        mvc.perform(as(get("/coordinator/classes/no-such-class/results"), coordinator)).andExpect(status().isNotFound());
        mvc.perform(as(get("/coordinator/children/no-such-child"), coordinator)).andExpect(status().isNotFound());
        mvc.perform(as(get("/coordinator/exams/no-such-exam/results"), coordinator)).andExpect(status().isNotFound());

        // And the window is bounded, like the calendar's.
        mvc.perform(as(get("/coordinator/classes/" + MINE + "/attendance?from=" + day + "&to=" + day.minusDays(1)), coordinator))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/coordinator/classes/" + MINE + "/attendance?from=2026-01-01&to=2026-12-01"), coordinator))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/coordinator/classes/" + MINE + "/attendance?from=yesterday"), coordinator))
                .andExpect(status().isBadRequest());
    }

    @Test void the_flagged_reads_are_404_while_their_feature_is_off_and_the_register_is_not() throws Exception {
        setFlag(adminToken, SCHOOL, FlagKeys.GRADEBOOK, false);
        setFlag(adminToken, SCHOOL, FlagKeys.EXAMS, false);
        try {
            for (String path : java.util.List.of("/coordinator/classes/" + MINE + "/results",
                    "/coordinator/lessons/" + LESSON + "/results", "/coordinator/children/" + kidMine,
                    "/coordinator/classes/" + MINE + "/exams", "/coordinator/exams/" + EXAM + "/results"))
                mvc.perform(as(get(path), coordinator)).andExpect(status().isNotFound());
            // The register carries no flag, for the reason `AttendanceController` carries none.
            mvc.perform(as(get("/coordinator/classes/" + MINE + "/attendance"), coordinator)).andExpect(status().isOk());
        } finally {
            setFlag(adminToken, SCHOOL, FlagKeys.GRADEBOOK, true);
            setFlag(adminToken, SCHOOL, FlagKeys.EXAMS, true);
        }
    }

    @Test void a_teacher_cannot_reach_the_coordinator_routes_and_nobody_may_write_them() throws Exception {
        mvc.perform(as(get("/coordinator/classes/" + MINE + "/results"), teacher)).andExpect(status().isForbidden());
        mvc.perform(as(post("/coordinator/classes/" + MINE + "/attendance").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), coordinator)).andExpect(status().isMethodNotAllowed());
    }

    // ---------------------------------------------------------------- fixture helpers

    /** The two routes answer the same JSON — the whole body, not a field of it. */
    private void assertSameBody(String teacherPath, String coordinatorPath) throws Exception {
        JsonNode hers = json(mvc.perform(as(get(teacherPath), teacher)).andExpect(status().isOk()).andReturn());
        JsonNode theirs = json(mvc.perform(as(get(coordinatorPath), coordinator)).andExpect(status().isOk()).andReturn());
        assertThat(theirs).as("%s must answer exactly what %s answers", coordinatorPath, teacherPath).isEqualTo(hers);
    }

    /** A coordinator account: a user row of the fourth role, with no teacher profile behind it. */
    private void staff(String id, String displayName) {
        var u = users.findById(id).orElseGet(quest.server.auth.Entities.UserEntity::new);
        u.setId(id); u.setSchoolId(SCHOOL); u.setEmail(id + "@seed.test"); u.setPasswordHash("x");
        u.setRole("COORDINATOR"); u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    /** One `staff_scopes` row: a subject and a track, which is what her reach is made of. */
    private void scopeRow(String id, String userId, String subject, String curriculum) {
        var row = staffScopes.findById(id).orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(id); row.setSchoolId(SCHOOL); row.setUserId(userId);
        row.setSubject(subject); row.setCurriculum(curriculum);
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
    }

    /** A closed exam on a section: the hand-scored lesson, typed `exam`, with its `exam_settings` row beside it. */
    private void examLesson(String id, ClassEntity section) {
        var l = lesson(id, SCHOOL, section, day);
        l.setType("exam"); l.setSubject(section.getSubject()); lessons.save(l);
        var now = Instant.now();
        var row = examSettings.findById(id).orElseGet(quest.server.exams.Entities.ExamSettingsEntity::new);
        row.setLessonId(id); row.setSchoolId(SCHOOL); row.setLevel(ExamLevels.ONE);
        row.setReleaseMode(ExamLevels.MANUAL);
        row.setOpensAt(now.minus(120, ChronoUnit.MINUTES)); row.setClosesAt(now.minus(60, ChronoUnit.MINUTES));
        row.setCreatedAt(now); row.setUpdatedAt(now);
        examSettings.save(row);
    }
}
