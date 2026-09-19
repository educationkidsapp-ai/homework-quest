package quest.server.grading;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §7's thresholds, at the boundaries. A band is what a parent and a teacher argue about, so every edge is pinned
 * here rather than left to whichever call site happens to exercise it: a threshold that moves has to move here too.
 */
class BandsTest {
    @Test void the_four_bands_are_inclusive_at_their_lower_bound() {
        assertThat(Bands.band(100)).isEqualTo(Bands.EXCEEDING);
        assertThat(Bands.band(Bands.EXCEEDING_AT)).isEqualTo(Bands.EXCEEDING);
        assertThat(Bands.band(Bands.EXCEEDING_AT - 1)).isEqualTo(Bands.SECURE);
        assertThat(Bands.band(Bands.SECURE_AT)).isEqualTo(Bands.SECURE);
        assertThat(Bands.band(Bands.SECURE_AT - 1)).isEqualTo(Bands.DEVELOPING);
        assertThat(Bands.band(Bands.DEVELOPING_AT)).isEqualTo(Bands.DEVELOPING);
        assertThat(Bands.band(Bands.DEVELOPING_AT - 1)).isEqualTo(Bands.EMERGING);
        assertThat(Bands.band(0)).isEqualTo(Bands.EMERGING);
    }

    @Test void the_stop_score_follows_the_stars() {
        assertThat(Bands.forStars(3)).isEqualTo(100);
        assertThat(Bands.forStars(2)).isEqualTo(70);
        assertThat(Bands.forStars(1)).isEqualTo(40);
        assertThat(Bands.forStars(0)).isZero();
        assertThat(Bands.forStars(9)).as("a star count out of range is clamped, never an exception").isEqualTo(100);
    }

    /**
     * The two aggregates §7 asks for, over one hand-computed set of four lesson scores, newest first:
     * <pre>
     *   scores (newest first)  20, 20, 100, 100     — she has fallen off lately
     *   gradebook average      (20+20+100+100)/4                    = 60  -> "secure"
     *   recency weights        2.000, 1.667, 1.333, 1.000  (sum 6)
     *   ChildLevel levelScore  (40 + 33.33 + 133.33 + 100) / 6      = 51  -> "developing"
     * </pre>
     * The column adds up to 60 and the child is described as developing, and both are right: one is what her marks
     * come to, the other is where she is now. Showing the second as the first is the bug this pins.
     */
    @Test void the_gradebook_average_is_the_plain_mean_and_the_level_score_is_not() {
        var newestFirst = List.of(20.0, 20.0, 100.0, 100.0);
        var homework = List.of(false, false, false, false);

        assertThat(Bands.mean(newestFirst)).as("a teacher adding the row up by hand gets this").isEqualTo(60.0);
        assertThat(Bands.band(Bands.mean(newestFirst))).isEqualTo(Bands.SECURE);

        assertThat(Bands.average(newestFirst, homework)).isCloseTo(51.1, org.assertj.core.api.Assertions.within(0.1));
        assertThat(Bands.band(Bands.average(newestFirst, homework))).isEqualTo(Bands.DEVELOPING);
    }

    /** The mean is over everything it is given; only the level score caps its window and weighs an exam twice. */
    @Test void the_plain_mean_never_drops_a_score_or_weighs_one() {
        var twelve = new java.util.ArrayList<>(Collections.nCopies(11, 100.0));
        twelve.add(0.0);                                                        // the twelfth, oldest

        assertThat(Bands.mean(twelve)).as("every scored cell of the window counts once").isCloseTo(91.7, org.assertj.core.api.Assertions.within(0.1));
        assertThat(Bands.average(twelve, Collections.nCopies(12, false)))
                .as("the level score looks at the newest ten only").isCloseTo(100.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(Bands.mean(List.of(100.0, 0.0)))
                .as("an exam counts once in a mean, twice in a level score").isEqualTo(50.0);
        assertThat(Bands.mean(List.of())).isNull();
    }

    @Test void the_average_is_weighted_toward_the_recent_scores() {
        var improving = List.of(90.0, 50.0);                                   // newest first
        var declining = List.of(50.0, 90.0);
        var flat = List.of(70.0, 70.0);

        assertThat(Bands.average(improving, List.of(false, false))).isGreaterThan(70.0);
        assertThat(Bands.average(declining, List.of(false, false))).isLessThan(70.0);
        assertThat(Bands.average(flat, List.of(false, false))).isCloseTo(70.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test void an_exam_counts_for_more_than_a_homework() {
        var scores = List.of(40.0, 100.0);

        double homeworkOnly = Bands.average(scores, List.of(false, false));
        double examFirst = Bands.average(scores, List.of(true, false));

        assertThat(examFirst).as("§8: exams count with a higher weight in the child's level").isLessThan(homeworkOnly);
    }

    @Test void only_the_last_ten_scores_count() {
        var eleven = new java.util.ArrayList<>(Collections.nCopies(10, 100.0));
        eleven.add(0.0);                                                        // the eleventh, oldest

        assertThat(Bands.average(eleven, Collections.nCopies(11, false)))
                .as("the eleventh score is outside the window")
                .isCloseTo(100.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test void a_trend_needs_four_scores_and_a_real_difference() {
        assertThat(Bands.trend(List.of(90.0, 40.0, 40.0))).as("three lessons are not a direction").isNull();
        assertThat(Bands.trend(List.of(90.0, 90.0, 40.0, 40.0))).isEqualTo(Bands.UP);
        assertThat(Bands.trend(List.of(40.0, 40.0, 90.0, 90.0))).isEqualTo(Bands.DOWN);
        assertThat(Bands.trend(List.of(70.0, 71.0, 70.0, 69.0))).as("a point of drift is not a direction").isEqualTo(Bands.FLAT);
    }

    @Test void no_scores_is_no_band_rather_than_a_zero() {
        assertThat(Bands.average(List.of(), List.of())).isNull();
        assertThat(Bands.trend(List.of())).isNull();
    }
}
