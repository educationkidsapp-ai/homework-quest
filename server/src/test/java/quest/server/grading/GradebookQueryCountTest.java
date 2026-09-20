package quest.server.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §10's "the gradebook for a class of 25 children and 20 lessons loads in under 1 s from the QA seed".
 *
 * <p>A wall-clock budget is not a test — it passes on a fast laptop and fails in CI for reasons that have nothing to
 * do with the code. What actually decides whether the grid is usable in a real school is whether its cost grows with
 * the roster, so that is what is measured: the statements one child and one lesson cost, then the same read over
 * 30 children × 20 lessons, asserted to be the same number. A score computed per cell, or the plays fetched per
 * lesson, moves it immediately.
 *
 * <p>The same rule is applied to the Results page and the child page, which have the same shape and the same trap.
 */
class GradebookQueryCountTest extends GradingTestSupport {
    private static final String A = "gq-school", TEACHER = "gq-teacher", CLASS_1A = "gq-1a";
    private static final int CHILDREN = 30, LESSONS = 20;

    @Override public String prefix() { return "gq-"; }

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    private String teacherToken, firstChild;
    private quest.server.tenancy.Entities.ClassEntity section;

    @BeforeEach void seed() throws Exception {
        school(A, "Query Academy", "GQSCHA");
        teacher(TEACHER, A, "Ms Sara");
        section = klass(CLASS_1A, A, TEACHER, "1A");
        lesson("gq-lesson-1", A, section, LocalDate.now().minusDays(1));

        enableGrading(adminToken(), A);
        teacherToken = token(TEACHER, "TEACHER", A);

        firstChild = child("Child 1", "GQSCHA", section);
        playTheSample(firstChild, "gq-lesson-1");
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void the_gradebook_costs_the_same_for_thirty_children_and_twenty_lessons_as_for_one() throws Exception {
        warmUp();
        long one = statements(this::gradebook);

        grow();

        assertThat(statements(this::gradebook))
                .as("the gradebook must not run a query per child, per lesson or per cell")
                .isEqualTo(one);
    }

    @Test void the_results_page_costs_the_same_for_a_full_class_as_for_one_child() throws Exception {
        warmUp();
        long one = statements(() -> mvc.perform(as(get("/teacher/lessons/gq-lesson-1/results"), teacherToken))
                .andExpect(status().isOk()));

        grow();

        assertThat(statements(() -> mvc.perform(as(get("/teacher/lessons/gq-lesson-1/results"), teacherToken))
                .andExpect(status().isOk())))
                .as("the Results page must not run a query per child or per stop").isEqualTo(one);
    }

    @Test void the_child_page_costs_the_same_for_twenty_lessons_as_for_one() throws Exception {
        warmUp();
        long one = statements(() -> mvc.perform(as(get("/teacher/children/" + firstChild), teacherToken))
                .andExpect(status().isOk()));

        grow();

        assertThat(statements(() -> mvc.perform(as(get("/teacher/children/" + firstChild), teacherToken))
                .andExpect(status().isOk())))
                .as("the child page must not run a query per lesson").isEqualTo(one);
    }

    /**
     * One call before the baseline is taken. The flag check in front of every route here reads
     * `FeatureFlags.effective`, which caches per school for a minute and is dropped when the fixture switches the
     * flags on — so the very first request of a test pays two reloads that no later one does, and the baseline
     * would be measuring the cache rather than the query shape.
     */
    private void warmUp() throws Exception {
        gradebook();
        mvc.perform(as(get("/teacher/lessons/gq-lesson-1/results"), teacherToken)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/children/" + firstChild), teacherToken)).andExpect(status().isOk());
    }

    /** The real shape: 30 children on the roster and 20 published lessons in the window, all of them played. */
    private void grow() throws Exception {
        for (int l = 2; l <= LESSONS; l++) lesson("gq-lesson-" + l, A, section, LocalDate.now().minusDays(l));
        for (int c = 2; c <= CHILDREN; c++) {
            String childId = child("Child " + c, "GQSCHA", section);
            for (int l = 1; l <= LESSONS; l++) playTheSample(childId, "gq-lesson-" + l);
        }
        for (int l = 2; l <= LESSONS; l++) playTheSample(firstChild, "gq-lesson-" + l);
    }

    private void gradebook() throws Exception {
        mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/gradebook?from=" + LocalDate.now().minusDays(LESSONS + 1)
                + "&to=" + LocalDate.now()), teacherToken)).andExpect(status().isOk());
    }

    private interface Call { void run() throws Exception; }

    /** Statements the call costs, measured on the Hibernate session factory, as `TeacherQueryCountTest` does. */
    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }
}
