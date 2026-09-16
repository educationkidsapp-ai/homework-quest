package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.children.Entities.AttemptEntity;

/**
 * `HomeQueryCountTest` pins how many <em>statements</em> a Home costs. This pins how many <em>rows</em> it
 * materialises, which is the other half and the one that actually breaks in production: a Home that loads every
 * attempt a school has ever recorded to print three skill names runs a constant number of queries right up to the
 * moment it exhausts the heap.
 *
 * <p>The teacher Home is the one at risk — `/` redirects every teacher to it — so the seed here grows the way a real
 * school does (one busy lesson, thousands of attempts) and the test asserts that Hibernate's entity load count does
 * not move, and that the figures themselves are windowed rather than merely fast.
 */
class HomeRowVolumeTest extends DashboardTestSupport {
    private static final String A = "vol-school-a";
    private static final String TEACHER = "vol-teacher-a";
    private static final String MATHS = A + ":british:1:math";
    private static final String LESSON = "vol-lesson-1";

    /** Enough that materialising them would be obvious in the entity count, and quick to insert on H2. */
    private static final int OLD_ATTEMPTS = 5_000;

    @Override String prefix() { return "vol-"; }

    @Autowired jakarta.persistence.EntityManagerFactory emf;

    private String childId;

    @BeforeEach void seed() throws Exception {
        school(A, "Volume Academy", "VOLUME");
        user(TEACHER, A, "teacher@vol.test", "TEACHER");
        teacherProfile(TEACHER, "[\"math\"]", "british", "[1]");
        klass(MATHS, A, "british", 1, "math", TEACHER);
        lessonWithSkill(LESSON, A, MATHS, "british", 1, "math", today(), "Counting on");

        childId = child("Hala", "VOLUME", "british", 1);
        // Five recent first tries, three wrong: the skill bands as NEEDS_ANOTHER_LOOK.
        for (int i = 0; i < 5; i++)
            attempt(childId, LESSON, stopId(LESSON), i >= 3, noon(today()).plusSeconds(i));
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void the_teacher_home_does_not_load_a_school_s_history_to_print_three_skill_names() throws Exception {
        String token = token(TEACHER, "TEACHER", A);

        var before = json(mvc.perform(as(get("/me/home"), token)).andExpect(status().isOk()).andReturn());
        assertThat(weakBand(before)).isEqualTo("NEEDS_ANOTHER_LOOK");
        long entitiesBefore = entitiesLoaded(() -> mvc.perform(as(get("/me/home"), token)).andExpect(status().isOk()));
        assertThat(entitiesBefore)
                .as("the Home loads the caller, her school and her classes as entities and reads the rest as "
                        + "aggregates — if this is ever 0 the measurement has stopped measuring anything")
                .isBetween(1L, 50L);

        // A year of history on the same lesson, every one of them correct and every one of them outside the window.
        addOldAttempts();

        long entitiesAfter = entitiesLoaded(() -> mvc.perform(as(get("/me/home"), token)).andExpect(status().isOk()));
        assertThat(entitiesAfter)
                .as("%d more attempt rows must not become %d more entities — the weakest-skills figure is a grouped "
                        + "query, not a scan of the school's history", OLD_ATTEMPTS, OLD_ATTEMPTS)
                .isEqualTo(entitiesBefore);

        // …and the window is a window, not just a performance trick: 5 000 correct answers from outside it would
        // swamp three wrong ones from inside and turn the band green if they were being counted.
        var after = json(mvc.perform(as(get("/me/home"), token)).andExpect(status().isOk()).andReturn());
        assertThat(weakBand(after)).as("attempts older than the window do not move the band").isEqualTo("NEEDS_ANOTHER_LOOK");
    }

    @Test void a_skill_too_few_children_have_tried_is_not_called_weak() throws Exception {
        String token = token(TEACHER, "TEACHER", A);
        // A second lesson with a single wrong answer: 0 % correct, but one try is not evidence of anything.
        lessonWithSkill("vol-lesson-2", A, MATHS, "british", 1, "math", today(), "Barely attempted");
        attempt(childId, "vol-lesson-2", stopId("vol-lesson-2"), false, noon(today()));

        var home = json(mvc.perform(as(get("/me/home"), token)).andExpect(status().isOk()).andReturn());
        var names = new ArrayList<String>();
        home.get("weakSkills").forEach(s -> names.add(s.get("name").asText()));
        assertThat(names).contains("Counting on")
                .as("one first try is noise, not the weakest skill in the school").doesNotContain("Barely attempted");
    }

    @Test void attempts_from_before_the_window_are_not_counted_at_all() throws Exception {
        String token = token(TEACHER, "TEACHER", A);
        // Ten correct answers 40 days ago would take the skill to 15/15 if the window were not applied.
        var old = new ArrayList<AttemptEntity>();
        for (int i = 0; i < 10; i++) old.add(oldAttempt(noon(today().minusDays(40)).plusSeconds(i)));
        attempts.saveAll(old);

        var home = json(mvc.perform(as(get("/me/home"), token)).andExpect(status().isOk()).andReturn());
        assertThat(weakBand(home)).isEqualTo("NEEDS_ANOTHER_LOOK");
    }

    // ---------------------------------------------------------------- helpers

    private static String weakBand(JsonNode home) {
        var weak = home.get("weakSkills");
        assertThat(weak).as("the seeded skill has enough first tries to be banded").isNotEmpty();
        return weak.get(0).get("band").asText();
    }

    /** Correct, and a year old: outside both the attempt window and any reasonable term. */
    private AttemptEntity oldAttempt(Instant at) {
        var a = new AttemptEntity();
        a.setId(prefix() + java.util.UUID.randomUUID()); a.setChildId(childId); a.setStopId(stopId(LESSON));
        a.setLessonId(LESSON); a.setLevel(1); a.setAnswerJson("{}"); a.setCorrect(true); a.setAttemptNumber(1);
        a.setMistakes(0); a.setStars(3); a.setAnsweredAt(at);
        return a;
    }

    private void addOldAttempts() {
        var batch = new ArrayList<AttemptEntity>(500);
        for (int i = 0; i < OLD_ATTEMPTS; i++) {
            batch.add(oldAttempt(noon(today().minusDays(200)).plusSeconds(i)));
            if (batch.size() == 500) { attempts.saveAll(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) attempts.saveAll(batch);
    }

    private interface Call { void run() throws Exception; }

    /** Entities Hibernate materialised during the call — the measure a statement count cannot give you. */
    private long entitiesLoaded(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getEntityLoadCount();
    }
}
