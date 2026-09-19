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
