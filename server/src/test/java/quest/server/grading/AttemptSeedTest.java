package quest.server.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.config.ApiException;

/**
 * `seed/attempts.csv`: the dashboard e2e suite asserts numbers, so the numbers have to be there and have to be the
 * same on every run. Both halves are checked — the file parses and refuses a broken row, and a load is idempotent.
 */
class AttemptSeedTest extends GradingTestSupport {
    private static final String A = "as-school", TEACHER = "as-teacher", TEACHER_B = "as-teacher-b";

    @Override String prefix() { return "as-"; }

    @Autowired AttemptSeed seed;

    private String teacherToken;

    @BeforeEach void setUp() throws Exception {
        school(A, "Seed Academy", "ASSCHA");
        teacher(TEACHER, A, "Ms Sara");
        teacher(TEACHER_B, A, "Ms Noor");                                      // one teacher per subject per class
        var a1 = klass("as-1a", A, TEACHER, "1A British");
        var b1 = klass("as-1b", A, TEACHER_B, "1B British");
        child("Amina Al Amin", "ASSCHA", a1);
        child("Zain Lutfi", "ASSCHA", a1);
        child("Rasha Hensley", "ASSCHA", b1);
        enableGrading(adminToken(), A);
        teacherToken = token(TEACHER, "TEACHER", A);
    }

    @AfterEach void clean() {
        var seeded = lessons.findAll().stream().filter(l -> l.getId().startsWith("seed-homework-as-")).toList();
        var ids = seeded.stream().map(quest.server.content.Entities.LessonEntity::getId).collect(java.util.stream.Collectors.toSet());
        attempts.deleteAll(attempts.findAll().stream().filter(a -> ids.contains(a.getLessonId())).toList());
        skills.deleteAll(skills.findAll().stream().filter(s -> ids.contains(s.getLessonId())).toList());
        stopsHolder.stops.deleteAll(stopsHolder.stops.findAll().stream().filter(s -> ids.contains(s.getLessonId())).toList());
        plays.deleteAll(plays.findAll().stream().filter(p -> ids.contains(p.getLessonId())).toList());
        lessons.deleteAll(seeded);
        removeSeed();
    }

    @Test void the_file_lands_as_attempts_the_results_page_can_score() throws Exception {
        assertThat(seed.load(A)).as("every row of the file").isEqualTo(10);

        var results = json(mvc.perform(as(get("/teacher/lessons/" + AttemptSeed.lessonIdOf("as-1a") + "/results"), teacherToken))
                .andExpect(status().isOk()).andReturn());

        assertThat(results.get("title").asText()).isEqualTo(AttemptSeed.LESSON_TITLE);
        assertThat(results.get("played").asInt()).isEqualTo(2);
        assertThat(results.get("needsMarking").asInt()).as("one retell per child, none of them marked").isEqualTo(2);
        var amina = children(results, "Amina Al Amin");
        assertThat(amina.get("autoScore").asInt()).as("two single-answer stops, both right first try").isEqualTo(100);
        var zain = children(results, "Zain Lutfi");
        assertThat(zain.get("autoScore").asInt()).as("one right, one wrong on the first try").isEqualTo(50);
    }

    @Test void a_second_run_writes_nothing() {
        assertThat(seed.load(A)).isEqualTo(10);
        assertThat(seed.load(A)).as("the ids are derived from the class and the child, so a re-run finds its own rows").isZero();
    }

    @Test void a_broken_row_names_the_line() {
        assertThatThrownBy(() -> AttemptSeed.parse("className,childName,stop,attemptNumber,correct,mistakes,stars\n1A,Maya,1,1,true,0\n"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("7 columns expected");

        assertThatThrownBy(() -> AttemptSeed.parse("className,childName,stop,attemptNumber,correct,mistakes,stars\n1A,Maya,9,1,true,0,3\n"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("stop must be 1-3");
    }

    @Test void a_class_the_file_names_and_the_school_does_not_have_is_a_refusal_not_a_silent_skip() {
        assertThatThrownBy(() -> seed.load("as-school-empty"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no class is named");
    }

    private static com.fasterxml.jackson.databind.JsonNode children(com.fasterxml.jackson.databind.JsonNode results, String name) {
        for (var node : results.get("children")) if (name.equals(node.get("name").asText())) return node;
        throw new AssertionError(name + " is not on the results page");
    }
}
