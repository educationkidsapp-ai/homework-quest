package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The usage, billing, classes, staff and platform-cost endpoints are grouped queries, not loops: their statement
 * count must not move when the school grows a teacher, a class, a lesson or a child, nor when the platform grows a
 * school. Measured with Hibernate statistics, as `ReportsQueryCountTest` does for the phase-1 reports.
 */
class UsageQueryCountTest extends DashboardTestSupport {
    private static final String A = "uq-school-a";
    private static final String TEACHER = "uq-teacher-a", MANAGER = "uq-manager-a";

    @Override String prefix() { return "uq-"; }

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @BeforeEach void seed() throws Exception {
        school(A, "Usage Academy", "USAGEA");
        user(TEACHER, A, "teacher@uq.test", "TEACHER");
        teacherProfile(TEACHER, "[\"math\"]", "british", "[1]");
        user(MANAGER, A, "manager@uq.test", "MANAGERIAL");
        klass(A + ":british:1:math", A, "british", 1, "math", TEACHER);
        lesson("uq-lesson-1", A, A + ":british:1:math", "british", 1, "math", today(), "published", noon(today()), 5_000);
        var child = child("Yara", "USAGEA", "british", 1);
        attempt(child, "uq-lesson-1", "uq-lesson-1:stop-1", true, noon(today()));
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void the_school_and_platform_reports_cost_the_same_however_much_there_is_to_count() throws Exception {
        String token = adminToken();
        String manager = token(MANAGER, "MANAGERIAL", A);

        long usageOne = statements(() -> mvc.perform(admin(get("/admin/schools/" + A + "/usage"), token)).andExpect(status().isOk()));
        long billingOne = statements(() -> mvc.perform(admin(get("/admin/schools/" + A + "/billing"), token)).andExpect(status().isOk()));
        long classesOne = statements(() -> mvc.perform(admin(get("/admin/schools/" + A + "/classes"), token)).andExpect(status().isOk()));
        long platformOne = statements(() -> mvc.perform(admin(get("/admin/usage/platform"), token)).andExpect(status().isOk()));
        long teachersOne = statements(() -> mvc.perform(as(get("/school/teachers"), manager)).andExpect(status().isOk()));
        long lessonsOne = statements(() -> mvc.perform(admin(get("/admin/lessons"), token)).andExpect(status().isOk()));

        grow();

        assertThat(statements(() -> mvc.perform(admin(get("/admin/schools/" + A + "/usage"), token)).andExpect(status().isOk())))
                .as("/admin/schools/{id}/usage must not run a query per teacher, lesson or day").isEqualTo(usageOne);
        assertThat(statements(() -> mvc.perform(admin(get("/admin/schools/" + A + "/billing"), token)).andExpect(status().isOk())))
                .as("/admin/schools/{id}/billing must not run a query per month or lesson").isEqualTo(billingOne);
        assertThat(statements(() -> mvc.perform(admin(get("/admin/schools/" + A + "/classes"), token)).andExpect(status().isOk())))
                .as("the Classes tab must not look a teacher up per row").isEqualTo(classesOne);
        assertThat(statements(() -> mvc.perform(admin(get("/admin/usage/platform"), token)).andExpect(status().isOk())))
                .as("/admin/usage/platform must not run a query per school").isEqualTo(platformOne);
        assertThat(statements(() -> mvc.perform(as(get("/school/teachers"), manager)).andExpect(status().isOk())))
                .as("/school/teachers must not run a query per teacher").isEqualTo(teachersOne);
        assertThat(statements(() -> mvc.perform(admin(get("/admin/lessons"), token)).andExpect(status().isOk())))
                .as("the school column of All lessons must not cost a query per row").isEqualTo(lessonsOne);
    }

    private void grow() throws Exception {
        for (int i = 1; i < COURSE_SLOTS.size(); i++) {
            int grade = slotGrade(i);
            String subject = slotSubject(i);
            String classId = A + ":british:" + grade + ":" + subject;
            klass(classId, A, "british", grade, subject, TEACHER);
            lesson("uq-grown-" + i, A, classId, "british", grade, subject, today().minusDays(i), "published",
                    noon(today().minusDays(i)), 1_000L * i);
        }
        for (int i = 2; i <= 4; i++) {
            school("uq-school-" + i, "Usage School " + i, "USAG0" + i);
            user("uq-teacher-" + i, A, "teacher" + i + "@uq.test", "TEACHER");
            teacherProfile("uq-teacher-" + i, "[\"math\"]", "british", "[1]");
            var child = child("Child " + i, "USAGEA", "british", 1);
            attempt(child, "uq-lesson-1", "uq-lesson-1:stop-1", true, noon(today()).plusSeconds(i));
        }
        klass(A + ":british:3:english:2", A, "british", 3, "english", "uq-teacher-2");
    }

    private interface Call { void run() throws Exception; }

    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }
}
