package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.tenancy.TenantContext;

/**
 * `GET /me/home` must cost the same number of statements whatever the school holds. A Home that looped a query per
 * class, per lesson, per child or per school would be fast in a test fixture and unusable in QA — so the count is
 * measured on a small seed, the seed is grown, and the count is asserted to be unchanged (as `ReportsQueryCountTest`
 * and `TenantMapTest` do for the reports and the map).
 */
class HomeQueryCountTest extends DashboardTestSupport {
    private static final String A = "hq-school-a";
    private static final String TEACHER = "hq-teacher-a", MANAGER = "hq-manager-a";

    @Override String prefix() { return "hq-"; }

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    private String child;

    @BeforeEach void seed() throws Exception {
        school(A, "Query Academy", "QUERYA");
        user(TEACHER, A, "teacher@hq.test", "TEACHER");
        teacherProfile(TEACHER, "[\"math\"]", "british", "[1]");
        user(MANAGER, A, "manager@hq.test", "MANAGERIAL");
        klass(A + ":british:1:math", A, "british", 1, "math", TEACHER);
        lessonWithSkill("hq-lesson-1", A, A + ":british:1:math", "british", 1, "math", today(), "Counting");
        child = child("Nour", "QUERYA", "british", 1);
        attempt(child, "hq-lesson-1", stopId("hq-lesson-1"), false, Instant.now().minus(2, ChronoUnit.HOURS));
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void every_home_costs_the_same_whatever_the_school_holds() throws Exception {
        String adminToken = adminToken();
        String teacherToken = token(TEACHER, "TEACHER", A);
        String managerToken = token(MANAGER, "MANAGERIAL", A);

        long adminOne = statements(() -> mvc.perform(admin(get("/me/home"), adminToken)).andExpect(status().isOk()));
        long scopedOne = statements(() -> mvc.perform(admin(get("/me/home"), adminToken).header(TenantContext.HEADER, A)).andExpect(status().isOk()));
        long teacherOne = statements(() -> mvc.perform(as(get("/me/home"), teacherToken)).andExpect(status().isOk()));
        long managerOne = statements(() -> mvc.perform(as(get("/me/home"), managerToken)).andExpect(status().isOk()));

        grow();

        assertThat(statements(() -> mvc.perform(admin(get("/me/home"), adminToken)).andExpect(status().isOk())))
                .as("the platform Home must not run a query per school or per lesson").isEqualTo(adminOne);
        assertThat(statements(() -> mvc.perform(admin(get("/me/home"), adminToken).header(TenantContext.HEADER, A)).andExpect(status().isOk())))
                .as("nor when the Admin has picked a school").isEqualTo(scopedOne);
        assertThat(statements(() -> mvc.perform(as(get("/me/home"), teacherToken)).andExpect(status().isOk())))
                .as("a teacher's Home must not run a query per class, lesson or child").isEqualTo(teacherOne);
        assertThat(statements(() -> mvc.perform(as(get("/me/home"), managerToken)).andExpect(status().isOk())))
                .as("a managerial Home must not run a query per teacher").isEqualTo(managerOne);
    }

    /** Four more classes, eight more lessons, two more teachers, two more children and their attempts. */
    private void grow() throws Exception {
        for (int i = 1; i < COURSE_SLOTS.size(); i++) {
            int grade = slotGrade(i);
            String subject = slotSubject(i);
            String classId = A + ":british:" + grade + ":" + subject;
            klass(classId, A, "british", grade, subject, TEACHER);
            lessonWithSkill("hq-grown-" + i, A, classId, "british", grade, subject, today().minusDays(i), "Skill " + i);
            lesson("hq-broken-" + i, A, classId, "british", grade, subject, today().minusDays(i), "error", null, 0);
        }
        school("hq-school-2", "Query Beta", "QUERYB");
        school("hq-school-3", "Query Gamma", "QUERYC");
        user("hq-teacher-2", A, "teacher2@hq.test", "TEACHER");
        user("hq-teacher-3", A, "teacher3@hq.test", "TEACHER");
        for (int i = 2; i <= 3; i++) {
            var more = child("Child " + i, "QUERYA", "british", 1);
            attempt(more, "hq-lesson-1", stopId("hq-lesson-1"), i % 2 == 0, Instant.now().minus(i, ChronoUnit.HOURS));
        }
    }

    private interface Call { void run() throws Exception; }

    /** Statements the call costs, measured on the Hibernate session factory. */
    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }
}
