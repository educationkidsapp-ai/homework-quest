package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quest.server.ClassFixtures;

/**
 * `GET /teacher/lessons` — §8's "All lessons" page, and the leak it closes.
 *
 * <p>The dashboard's All-lessons screen called `GET /admin/lessons` with a teacher's token, because
 * `permissions.json` grants her `lesson.read`. That route is scoped to the tenant and to nothing else, so Sara was
 * shown every lesson her school holds: Omar's Year 3 homework, and the maths another teacher wrote for a class Sara
 * has never taught. It is the same shape of leak N2.3b (#70) fixed for the student lists, and the fix is the same
 * one — the list is reduced to what {@link quest.server.tenancy.TeacherScope#requireLesson} would open.
 *
 * <p>Two ways in, and no third: she wrote it, or she holds the assignment on its class <em>and</em> its subject.
 * Each row below is one of those cases or one of the near misses — a colleague's lesson on a class she does teach
 * (in), the same class in a subject she does not (out), her own draft that names no class (in).
 */
class TeacherLessonListTest extends TeacherTestSupport {
    private static final String SCHOOL = "tll-school";
    private static final String SARA = "tll-sara", OMAR = "tll-omar", NADIA = "tll-nadia", NEW = "tll-new";
    private static final String A1 = "tll-1a", B1 = "tll-1b", A3 = "tll-3a", B3 = "tll-3b", A2 = "tll-2a";

    /** Join codes are unique platform-wide and the suite shares one database, so they come from one counter. */
    private static final java.util.concurrent.atomic.AtomicInteger CODES = new java.util.concurrent.atomic.AtomicInteger();

    @Override String prefix() { return "tll-"; }

    @org.springframework.beans.factory.annotation.Autowired jakarta.persistence.EntityManagerFactory emf;

    private String saraToken, omarToken, nadiaToken, newToken;

    @BeforeEach void seed() {
        school(SCHOOL, "List Academy", "TLLSCH");
        teacher(SARA, SCHOOL, "sara@tll.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        teacher(OMAR, SCHOOL, "omar@tll.test", "Mr Omar", "[\"math\"]", "british", "[3]");
        teacher(NADIA, SCHOOL, "nadia@tll.test", "Ms Nadia", "[\"math\"]", "british", "[2]");
        teacher(NEW, SCHOOL, "new@tll.test", "Ms Hala", "[\"math\"]", "british", "[1]");
        section(A1, "1A", 1, "math", SARA); section(B1, "1B", 1, "math", SARA);
        section(A3, "3A", 3, "math", OMAR); section(B3, "3B", 3, "math", OMAR);
        section(A2, "2A", 2, "math", NADIA);
        lesson("tll-l-1a", A1, 1, "math", SARA, LocalDate.now());
        lesson("tll-l-1b", B1, 1, "math", SARA, LocalDate.now().minusDays(3));
        // A colleague's lesson on a class and subject Sara holds the assignment for: hers to open (a co-teacher's).
        lesson("tll-l-1a-coteach", A1, 1, "math", NEW, LocalDate.now().minusDays(1));
        // The same class, in the subject she does not teach there — Ms Hala has 1A English: out.
        ClassFixtures.assign(assignments, classes.findById(A1).orElseThrow(), "english", NEW);
        lesson("tll-l-1a-english", A1, 1, "english", NEW, LocalDate.now());
        lesson("tll-l-3a", A3, 3, "math", OMAR, LocalDate.now());
        lesson("tll-l-3b", B3, 3, "math", OMAR, LocalDate.now().minusDays(2));
        // Another teacher's maths, on a class Sara is not assigned to at all.
        lesson("tll-l-2a", A2, 2, "math", NADIA, LocalDate.now());
        // A draft of her own that names no class yet, and one of Omar's: an unclassed lesson is only its author's.
        lesson("tll-l-loose-sara", null, 1, "math", SARA, LocalDate.now());
        lesson("tll-l-loose-omar", null, 3, "math", OMAR, LocalDate.now());

        saraToken = token(SARA, "TEACHER", SCHOOL); omarToken = token(OMAR, "TEACHER", SCHOOL);
        nadiaToken = token(NADIA, "TEACHER", SCHOOL); newToken = token(NEW, "TEACHER", SCHOOL);
    }

    @AfterEach void clean() {
        removeSeed();
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getClassId().startsWith(prefix())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getId().startsWith(prefix())).toList());
    }

    @Test void a_teacher_sees_her_own_lessons_and_her_assignments_and_nobody_elses() throws Exception {
        assertThat(ids(saraToken, "")).containsExactlyInAnyOrder(
                "tll-l-1a", "tll-l-1b", "tll-l-1a-coteach", "tll-l-loose-sara");

        assertThat(ids(omarToken, "")).containsExactlyInAnyOrder("tll-l-3a", "tll-l-3b", "tll-l-loose-omar");

        // The unassigned maths teacher: her own class only, and none of Sara's or Omar's.
        assertThat(ids(nadiaToken, "")).containsExactly("tll-l-2a");
    }

    /** The two rows that are the point of the rule, asserted on their own so a regression names itself. */
    @Test void the_rows_that_are_nearly_hers() throws Exception {
        // Ms Hala keeps both: 1A English is her assignment, and she wrote the maths one Sara can also open.
        assertThat(ids(newToken, "")).contains("tll-l-1a-english", "tll-l-1a-coteach");
        // She holds the assignment but did not write it: she may open it.
        assertThat(ids(saraToken, "")).contains("tll-l-1a-coteach").doesNotContain("tll-l-1a-english");
    }

    @Test void the_filters_are_the_admin_pages_and_narrow_what_she_may_already_see() throws Exception {
        assertThat(ids(saraToken, "?classId=" + A1)).containsExactlyInAnyOrder("tll-l-1a", "tll-l-1a-coteach");
        assertThat(ids(saraToken, "?grade=1")).containsExactlyInAnyOrder(
                "tll-l-1a", "tll-l-1b", "tll-l-1a-coteach", "tll-l-loose-sara");
        assertThat(ids(saraToken, "?subject=math&curriculum=british")).containsExactlyInAnyOrder(
                "tll-l-1a", "tll-l-1b", "tll-l-1a-coteach", "tll-l-loose-sara");
        assertThat(ids(saraToken, "?subject=english")).isEmpty();
        assertThat(ids(saraToken, "?from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now()))
                .containsExactlyInAnyOrder("tll-l-1a", "tll-l-1a-coteach", "tll-l-loose-sara");

        // Naming a class that is not hers narrows to nothing rather than reaching past the rule.
        assertThat(ids(saraToken, "?classId=" + A3)).isEmpty();
        mvc.perform(as(get("/teacher/lessons?subject=astronomy"), saraToken)).andExpect(status().isBadRequest());
    }

    @Test void a_teacher_with_no_lessons_yet_gets_an_empty_list() throws Exception {
        lessons.deleteAll(lessons.findAll().stream().filter(l -> l.getId().startsWith("tll-l-")).toList());
        assertThat(ids(saraToken, "")).isEmpty();
    }

    /**
     * The shape that would be an N+1: asking, for each of her lessons, whether she is assigned to its class. Her
     * assignments are read once instead, so a teacher with thirty sections costs what a teacher with two costs —
     * measured the way `TeacherQueryCountTest` measures the other teacher loops.
     */
    @Test void the_list_costs_the_same_for_thirty_sections_as_for_two() throws Exception {
        long few = statements(() -> mvc.perform(as(get("/teacher/lessons"), saraToken)).andExpect(status().isOk()));

        for (int i = 0; i < 30; i++) {
            section("tll-grow-" + i, "1G" + i, 1, "math", SARA);
            lesson("tll-l-grow-" + i, "tll-grow-" + i, 1, "math", SARA, LocalDate.now().minusDays(i % 20));
        }
        assertThat(ids(saraToken, "")).hasSize(34);

        assertThat(statements(() -> mvc.perform(as(get("/teacher/lessons"), saraToken)).andExpect(status().isOk())))
                .as("the list must not ask about an assignment per lesson").isEqualTo(few);
    }

    // ---------------------------------------------------------------- helpers

    private interface Call { void run() throws Exception; }

    /** Statements the call costs, measured on the Hibernate session factory. */
    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }

    private List<String> ids(String token, String query) throws Exception {
        JsonNode rows = json(mvc.perform(as(get("/teacher/lessons" + query), token)).andExpect(status().isOk()).andReturn());
        var out = new ArrayList<String>();
        for (var row : rows) out.add(row.get("id").asText());
        return out;
    }

    private void section(String id, String name, int grade, String subject, String teacherId) {
        var k = classes.findById(id).orElseGet(() -> {
            var fresh = new quest.server.tenancy.Entities.ClassEntity();
            fresh.setId(id); fresh.setSchoolId(SCHOOL); fresh.setCurriculum("british"); fresh.setGrade(grade);
            fresh.setName(name); fresh.setJoinCode("TLL" + String.format("%04d", CODES.incrementAndGet()));
            fresh.setActive(true); fresh.setJoinCodeEnabled(true); fresh.setCreatedAt(java.time.Instant.now());
            return classes.save(fresh);
        });
        ClassFixtures.assign(assignments, k, subject, teacherId);
    }

    private void lesson(String id, String classId, int grade, String subject, String teacherId, LocalDate date) {
        var l = lesson(id, SCHOOL, classId, "british", grade, subject, date, "draft");
        l.setTeacherId(teacherId);
        lessons.save(l);
    }
}
