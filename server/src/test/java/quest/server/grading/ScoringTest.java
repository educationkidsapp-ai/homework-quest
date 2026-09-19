package quest.server.grading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import quest.api.dto.Bilingual;
import quest.api.dto.Ingredient;
import quest.api.dto.Stop;
import quest.api.dto.Tile;
import quest.server.children.Entities.AttemptEntity;

/**
 * The teacher prompt §7's rules, on one hand-computed lesson. No Spring: {@link Scoring} is a pure function of the
 * attempts, the stops and the marks, and that is exactly what makes "recomputed whenever new attempts arrive" true
 * of every reader without a stored copy to keep in step.
 *
 * <p><strong>The sample</strong> (Level 1, four stops), the one written out in the PR body:
 * <pre>
 *   s1  choice     (single)  first try correct, 3 stars          -> 100
 *   s2  choice     (single)  first try wrong (1*), retry right   ->   0   first try, never the best try
 *   s3  selectAll  (multi)   one attempt, 2 stars                ->  70   §7: 3* = 100, 2* = 70, 1* = 40
 *   s4  retell     (open)    answered, not yet marked            ->   -   left out, counted as needing a mark
 *   autoScore = (100 + 0 + 70) / 3 = 56.67 -> 57  ->  band "developing"
 *   stars 3 + 2 + 2 + 3 = 10 of 12, completion 4/4 = 100 %, level reached 1, needsMarking 1
 * </pre>
 */
class ScoringTest {
    private static final String CHILD = "child-1", LESSON = "lesson-1";

    private static final Map<Integer, List<Stop>> STOPS = Map.of(1, List.of(
            choice("s1"), choice("s2"), selectAll("s3"), retell("s4")));

    /** First try correct, first try wrong then right, two stars, and one open stop nobody has looked at. */
    private static List<AttemptEntity> sample() {
        return List.of(attempt("s1", 1, true, 3), attempt("s2", 1, false, 1), attempt("s2", 2, true, 2),
                attempt("s3", 1, true, 2), attempt("s4", 1, true, 3));
    }

    @Test void the_hand_computed_sample() {
        var score = Scoring.of(CHILD, LESSON, STOPS, sample(), Map.of(), null);

        assertThat(score.attempted()).isTrue();
        assertThat(score.scoredLevel()).isEqualTo(1);
        assertThat(score.levelReached()).as("every stop of Level 1 was answered").isEqualTo(1);
        assertThat(score.autoScore()).as("(100 + 0 + 70) / 3, rounded").isEqualTo(57);
        assertThat(score.score()).isEqualTo(57);
        assertThat(score.band()).isEqualTo(Bands.DEVELOPING);
        assertThat(score.starsEarned()).isEqualTo(10);
        assertThat(score.starsTotal()).isEqualTo(12);
        assertThat(score.completion()).isEqualTo(100);
        assertThat(score.needsMarking()).as("the retell is complete but unscored until she marks it").isEqualTo(1);

        var s2 = score.stops().stream().filter(s -> s.stopId().equals("s2")).findFirst().orElseThrow();
        assertThat(s2.firstTryCorrect()).as("§7 scores a single-answer stop on the first try").isFalse();
        assertThat(s2.score()).isZero();
        assertThat(s2.stars()).as("her best stars are still reported, they just do not score the stop").isEqualTo(2);
    }

    @Test void marking_the_open_stop_moves_the_score_and_clears_the_badge() {
        var marked = Scoring.of(CHILD, LESSON, STOPS, sample(), Map.of("s4", new Scoring.Mark(2, null, "Lovely retelling.")), null);

        assertThat(marked.needsMarking()).isZero();
        assertThat(marked.autoScore()).as("(100 + 0 + 70 + 70) / 4").isEqualTo(60);
        assertThat(marked.band()).isEqualTo(Bands.SECURE);
    }

    @Test void a_teacher_score_overrides_the_automatic_one_and_keeps_it_visible() {
        var overridden = Scoring.of(CHILD, LESSON, STOPS, sample(), Map.of(), new Scoring.Mark(null, 75, "Well done."));

        assertThat(overridden.autoScore()).as("§7: the automatic score stays visible beside the override").isEqualTo(57);
        assertThat(overridden.teacherScore()).isEqualTo(75);
        assertThat(overridden.score()).isEqualTo(75);
        assertThat(overridden.band()).isEqualTo(Bands.SECURE);
    }

    @Test void a_child_who_has_not_played_is_not_a_child_who_scored_nothing() {
        var none = Scoring.of(CHILD, LESSON, STOPS, List.of(), Map.of(), null);

        assertThat(none.attempted()).isFalse();
        assertThat(none.score()).isNull();
        assertThat(none.band()).isNull();
        assertThat(none.completion()).isZero();
    }

    /** Half a lesson is two numbers: how well she did on what she did, and how much of it she did. */
    @Test void a_half_played_lesson_scores_what_she_answered_and_reports_the_rest_as_completion() {
        var half = Scoring.of(CHILD, LESSON, STOPS, List.of(attempt("s1", 1, true, 3), attempt("s2", 1, true, 3)), Map.of(), null);

        assertThat(half.autoScore()).as("both answered stops were right").isEqualTo(100);
        assertThat(half.completion()).isEqualTo(50);
        assertThat(half.levelReached()).as("she has not finished the level").isZero();
        assertThat(half.needsMarking()).as("an open stop she never reached is not waiting for a mark").isZero();
    }

    /** An information stop is not a question: it counts toward completion and never toward the score. */
    @Test void information_stops_are_completion_only() {
        Map<Integer, List<Stop>> withInfo = Map.of(1, List.of(readPage("i1"), choice("s1")));
        var score = Scoring.of(CHILD, LESSON, withInfo, List.of(attempt("i1", 1, true, 3), attempt("s1", 1, false, 0)), Map.of(), null);

        assertThat(score.autoScore()).as("only the question scored, and it was wrong").isZero();
        assertThat(score.stops()).extracting(Scoring.StopOutcome::stopId).containsExactly("s1");
        assertThat(score.completion()).isEqualTo(100);
    }

    /** The exit ticket is a container — the child answers its questions and the attempts carry their ids. */
    @Test void an_exit_ticket_is_scored_through_the_questions_inside_it() {
        var flattened = Scoring.scorable(List.of(new Stop.ExitTicket("e1", "Exit", "Last one", ingredient(), tip(),
                List.of(choice("q1"), choice("q2")), null, null)));

        assertThat(flattened).extracting(Stop::getId).containsExactly("q1", "q2");
    }

    /** The highest level she has touched is the one she is described by; the easy level below it is history. */
    @Test void the_score_is_about_the_hardest_level_she_attempted() {
        Map<Integer, List<Stop>> two = Map.of(1, List.of(choice("a1")), 2, List.of(choice("b1")));
        var score = Scoring.of(CHILD, LESSON, two, List.of(attempt("a1", 1, true, 3), attempt("b1", 1, false, 0)), Map.of(), null);

        assertThat(score.scoredLevel()).isEqualTo(2);
        assertThat(score.autoScore()).isZero();
    }

    // ---------------------------------------------------------------- fixtures

    private static AttemptEntity attempt(String stopId, int number, boolean correct, int stars) {
        var a = new AttemptEntity();
        a.setId(stopId + "-" + number); a.setChildId(CHILD); a.setLessonId(LESSON); a.setStopId(stopId); a.setLevel(1);
        a.setAnswerJson("{}"); a.setCorrect(correct); a.setAttemptNumber(number); a.setMistakes(correct ? 0 : 1);
        a.setStars(stars); a.setAnsweredAt(Instant.parse("2026-09-01T10:0" + number + ":00Z"));
        return a;
    }

    private static Ingredient ingredient() { return new Ingredient("🥕", "carrot"); }
    private static Bilingual tip() { return new Bilingual("Count together.", "Count together."); }

    private static Stop.Choice choice(String id) {
        return new Stop.Choice(id, id, "Which one?", ingredient(), tip(), "Count first.", "Which is bigger?",
                List.of(new Tile("a", "A", null, null), new Tile("b", "B", null, null)), "a", null, null);
    }

    private static Stop.SelectAll selectAll(String id) {
        return new Stop.SelectAll(id, id, "Pick them all", ingredient(), tip(), "Which are even?",
                List.of(new Tile("a", "2", null, null), new Tile("b", "3", null, null)), List.of("a"), null, null);
    }

    private static Stop.Retell retell(String id) {
        return new Stop.Retell(id, id, "Tell it back", ingredient(), tip(), "What happened?", List.of(), "We counted.", true, null, null);
    }

    private static Stop.ReadPage readPage(String id) {
        return new Stop.ReadPage(id, id, "Read with me", ingredient(), tip(), 1, List.of("Once upon a time."),
                null, null, null, null, null, null);
    }
}
