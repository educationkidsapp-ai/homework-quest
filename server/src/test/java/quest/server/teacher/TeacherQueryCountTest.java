package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.flags.FlagKeys;

/**
 * The two P4.0 reads that loop over rows must cost the same number of statements whatever the school holds.
 *
 * <p>Both have an obvious shape that is an N+1: "My students" would ask `ProgressService` per child, and the map's
 * teacher islands would ask for each question's stops and its teacher one at a time. Either is fast on a fixture of
 * one and unusable in a school of four hundred — so the count is measured on a small seed, the seed is grown, and
 * the count is asserted to be unchanged, exactly as `HomeQueryCountTest` and `TenantMapTest` do for the Homes and
 * the map.
 */
class TeacherQueryCountTest extends TeacherTestSupport {
    private static final String A = "qc-school-a";
    private static final String TEACHER_A = "qc-teacher-a";
    private static final String CLASS_A1 = "qc-school-a:british:1:math";

    @Override String prefix() { return "qc-"; }

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    private String adminToken, teacherToken, firstChild;

    @BeforeEach void seed() throws Exception {
        school(A, "Query Academy", "QCSCHA");
        teacher(TEACHER_A, A, "a@qc.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        klass(CLASS_A1, A, "british", 1, "math", TEACHER_A);
        lessonWithSkill("qc-lesson-1", A, CLASS_A1, "british", 1, "math", LocalDate.now().minusDays(2), "Counting");

        adminToken = adminToken();
        setFlag(adminToken, A, FlagKeys.TEACHER_QUESTIONS, true);
        teacherToken = token(TEACHER_A, "TEACHER", A);

        firstChild = child("Maya", "QCSCHA", "british", 1);
        attempt(firstChild, "qc-lesson-1", stopId("qc-lesson-1"), false, Instant.now().minus(2, ChronoUnit.HOURS));
        sendQuestion("First question");
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void my_students_costs_the_same_for_one_child_as_for_many() throws Exception {
        long one = statements(() -> mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), teacherToken))
                .andExpect(status().isOk()));

        growChildren();

        assertThat(statements(() -> mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/students"), teacherToken))
                .andExpect(status().isOk())))
                .as("My students must not run a query per child, lesson or skill").isEqualTo(one);
    }

    @Test void the_map_islands_cost_the_same_for_one_question_as_for_many() throws Exception {
        long one = statements(() -> map(firstChild));

        for (int i = 2; i <= 5; i++) sendQuestion("Question " + i);

        assertThat(statements(() -> map(firstChild)))
                .as("the teacher islands must not run a query per question or per teacher").isEqualTo(one);
    }

    /** The teacher's own list is the third loop: one summary per question would be a query per question. */
    @Test void her_question_list_costs_the_same_for_one_question_as_for_many() throws Exception {
        long one = statements(() -> mvc.perform(as(get("/teacher/questions"), teacherToken)).andExpect(status().isOk()));

        for (int i = 2; i <= 5; i++) sendQuestion("Question " + i);
        growChildren();

        assertThat(statements(() -> mvc.perform(as(get("/teacher/questions"), teacherToken)).andExpect(status().isOk())))
                .as("the questions list must not run a summary query per question").isEqualTo(one);
    }

    // ---------------------------------------------------------------- growing the seed

    /** Four more children in the class, each with an attempt, and a second published lesson with its own skill. */
    private void growChildren() throws Exception {
        lessonWithSkill("qc-lesson-2", A, CLASS_A1, "british", 1, "math", LocalDate.now().minusDays(3), "Adding");
        for (int i = 2; i <= 5; i++) {
            var more = child("Child " + i, "QCSCHA", "british", 1);
            attempt(more, "qc-lesson-1", stopId("qc-lesson-1"), i % 2 == 0, Instant.now().minus(i, ChronoUnit.HOURS));
            attempt(more, "qc-lesson-2", stopId("qc-lesson-2"), i % 2 == 1, Instant.now().minus(i, ChronoUnit.HOURS));
        }
    }

    private void sendQuestion(String title) throws Exception {
        String stops = stopsJson(choice(prefix() + java.util.UUID.randomUUID().toString().substring(0, 8), title));
        var created = json(mvc.perform(as(post("/teacher/questions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"stops\":" + stops + ",\"classIds\":[\"" + CLASS_A1 + "\"],"
                        + "\"from\":\"" + LocalDate.now().minusDays(1) + "\",\"to\":\"" + LocalDate.now().plusDays(7) + "\"}"),
                teacherToken)).andExpect(status().isCreated()).andReturn());
        mvc.perform(as(post("/teacher/questions/" + created.get("id").asText() + "/send"), teacherToken))
                .andExpect(status().isOk());
    }

    private void map(String childId) throws Exception {
        mvc.perform(get("/children/" + childId + "/map?from=" + LocalDate.now().minusDays(7)
                + "&to=" + LocalDate.now().plusDays(7) + "&today=" + LocalDate.now())
                .header("Authorization", PARENT)).andExpect(status().isOk());
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
