package quest.server.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.flags.FlagKeys;

/**
 * The Exams tab costs the same for a term's worth of exams as for one.
 *
 * <p><strong>What this is defending.</strong> N4.4's dashboard drew the State, Sat and Needs-marking columns by
 * calling `GET /teacher/exams/{id}/results` once per row, capped at six — a whole scoring pass, with its plays, its
 * attempts and its marks, six times over, to fill three integers per line. The columns now come from the list
 * itself, and this test is what stops them quietly going back: the statements one exam costs are measured, the
 * class is grown to twelve exams and a played roster, and the count must not have moved.
 *
 * <p>A wall-clock budget would pass on a fast laptop and fail in CI for reasons that have nothing to do with the
 * code; what decides whether the tab is usable in a real school is whether its cost grows with the term, which is
 * exactly what is asserted here. `GradebookQueryCountTest` makes the same argument at greater length.
 */
class ExamListQueryCountTest extends ExamTestSupport {
    private static final String A = "exq-school", TEACHER = "exq-teacher", CLASS_1A = "exq-1a";
    private static final int EXAMS = 12, CHILDREN = 8;

    @Override public String prefix() { return "exq-"; }

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    private String teacherToken, firstChild;
    private quest.server.tenancy.Entities.ClassEntity section;

    @BeforeEach void seed() throws Exception {
        school(A, "Query Academy", "EXQSCH");
        teacher(TEACHER, A, "Ms Sara");
        section = klass(CLASS_1A, A, TEACHER, "1A");

        var adminToken = adminToken();
        enableGrading(adminToken, A);
        setFlag(adminToken, A, FlagKeys.EXAMS, true);
        teacherToken = token(TEACHER, "TEACHER", A);

        firstChild = child("Child 1", "EXQSCH", section);
        publishedExam("exq-exam-1");
        playTheSample(firstChild, "exq-exam-1");
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void the_exams_tab_costs_the_same_for_a_term_of_exams_as_for_one() throws Exception {
        warmUp();
        long one = statements(this::tab);

        for (int e = 2; e <= EXAMS; e++) publishedExam("exq-exam-" + e);
        for (int c = 2; c <= CHILDREN; c++) {
            String childId = child("Child " + c, "EXQSCH", section);
            for (int e = 1; e <= EXAMS; e++) playTheSample(childId, "exq-exam-" + e);
        }
        for (int e = 2; e <= EXAMS; e++) playTheSample(firstChild, "exq-exam-" + e);

        assertThat(json(mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/exams"), teacherToken))
                .andExpect(status().isOk()).andReturn())).hasSize(EXAMS);
        assertThat(statements(this::tab))
                .as("the Exams tab must not run a query per exam — not for its class, its sittings or its marking")
                .isEqualTo(one);
    }

    @Test void one_exam_read_on_its_own_costs_no_more_than_the_tab_does() throws Exception {
        warmUp();
        assertThat(statements(() -> mvc.perform(as(get("/teacher/exams/exq-exam-1"), teacherToken))
                .andExpect(status().isOk()))).isLessThanOrEqualTo(statements(this::tab));
    }

    /**
     * One call before the baseline is taken: the flag check in front of every route here reads
     * `FeatureFlags.effective`, which caches per school for a minute and is dropped when the fixture switches the
     * flags on, so the first request of a test pays reloads no later one does.
     */
    private void warmUp() throws Exception {
        tab();
        mvc.perform(as(get("/teacher/exams/exq-exam-1"), teacherToken)).andExpect(status().isOk());
    }

    private void tab() throws Exception {
        mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/exams"), teacherToken)).andExpect(status().isOk());
    }

    /** A published `type = exam` lesson over Level 1, open right now. */
    private void publishedExam(String id) throws Exception {
        readyToPublish(id, A, section, LocalDate.now(), "exam");
        exam(id, A, ExamLevels.ONE, -30, 30, ExamLevels.MANUAL);
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/teacher/lessons/" + id + "/publish"), teacherToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"classIds\":[\"" + CLASS_1A + "\"]}"))
                .andExpect(status().isOk());
    }

    private interface Call { void run() throws Exception; }

    /** Statements the call costs, measured on the Hibernate session factory, as `GradebookQueryCountTest` does. */
    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }
}
