package quest.server.grading;

import java.util.List;

/**
 * Every number the teacher prompt §7 leaves to "constants in `server/.../grading/Bands.java`", in one place so
 * Admin can be given a screen over them later without touching the arithmetic that reads them.
 *
 * <p><strong>Stop scores.</strong> §7 gives two rules and this file gives them names: a single-answer stop scores by
 * <em>first-try</em> correctness ({@link #CORRECT} or {@link #WRONG}), a multi-answer, match, order or trace stop by
 * the stars it earned ({@link #forStars}) — "3 stars = 100, 2 = 70, 1 = 40", and nothing earned is nothing scored.
 * An information stop is not a question and is never scored; an open stop is complete but unscored until the teacher
 * marks it, and her 1–3 stars then run through the same {@link #forStars}.
 *
 * <p><strong>Level bands.</strong> §7's four words over a 0–100 score. The two upper thresholds are deliberately the
 * ones {@link quest.api.progress.ProgressBands} already draws the parent's three bands at (0.85 and 0.60 there), so
 * a child the app calls "going well" is not a child the gradebook calls "developing"; {@link #DEVELOPING} then
 * splits what is left, because §7 wants four words where the app wants three.
 *
 * <p><strong>The child's level.</strong> A rolling read of her last {@link #WINDOW} homework scores in one subject,
 * weighted toward the recent ones ({@link #weight}) and with an exam counted {@link #EXAM_WEIGHT} times a homework,
 * which is §8's "exams count with a higher weight". {@link #trend} compares the newer half of that window with the
 * older half and needs {@link #TREND_POINTS} to say anything at all — two lessons are not a direction.
 */
public final class Bands {
    private Bands() {}

    // ---------------------------------------------------------------- stop scores (§7)

    /** A single-answer stop answered correctly on the first try, and one that was not. */
    public static final int CORRECT = 100, WRONG = 0;
    /** §7 literally: "3 stars = 100, 2 = 70, 1 = 40". Index by the stars earned. */
    private static final int[] BY_STARS = {0, 40, 70, 100};

    public static int forStars(int stars) { return BY_STARS[Math.max(0, Math.min(3, stars))]; }

    // ---------------------------------------------------------------- level bands (§7)

    public static final String EMERGING = "emerging", DEVELOPING = "developing", SECURE = "secure", EXCEEDING = "exceeding";
    /** The lower bound of each band, in score points. Below {@link #DEVELOPING_AT} is `emerging`. */
    public static final int EXCEEDING_AT = 85, SECURE_AT = 60, DEVELOPING_AT = 40;

    public static String band(double score) {
        return score >= EXCEEDING_AT ? EXCEEDING : score >= SECURE_AT ? SECURE : score >= DEVELOPING_AT ? DEVELOPING : EMERGING;
    }

    // ---------------------------------------------------------------- the child's level (§7, §8)

    /** §7: "the last 10 homework scores (weighted toward recent)". */
    public static final int WINDOW = 10;
    /** §8: an exam counts for more than a homework in the child's level. */
    public static final double EXAM_WEIGHT = 2.0;
    /** The newest score of the window weighs this much more than the oldest — a straight-line ramp. */
    public static final double RECENCY_RANGE = 1.0;
    public static final String UP = "up", FLAT = "flat", DOWN = "down";
    /** Below this many scores there is no trend to report, only a band. */
    public static final int TREND_POINTS = 4;
    /** Half-window averages closer together than this are the same: a point of drift is not a direction. */
    public static final double TREND_EPSILON = 5.0;

    /**
     * The recency weight of the score at `index` in a window of `size`, newest first. The oldest weighs 1 and the
     * newest 1 + {@link #RECENCY_RANGE}, so a child who has just turned a corner is described by where she is now
     * rather than by the fortnight behind her — and a window of one is not divided by zero.
     */
    public static double weight(int index, int size) {
        if (size <= 1) return 1.0 + RECENCY_RANGE;
        return 1.0 + RECENCY_RANGE * (size - 1 - index) / (double) (size - 1);
    }

    /** A weighted mean of the newest {@link #WINDOW} scores, newest first, or null when there are none. */
    public static Double average(List<Double> newestFirst, List<Boolean> isExam) {
        int size = Math.min(WINDOW, newestFirst.size());
        if (size == 0) return null;
        double sum = 0, weights = 0;
        for (int i = 0; i < size; i++) {
            double w = weight(i, size) * (isExam.get(i) ? EXAM_WEIGHT : 1.0);
            sum += newestFirst.get(i) * w; weights += w;
        }
        return sum / weights;
    }

    /** `up`, `flat` or `down` from the newest {@link #WINDOW} scores, newest first; null below {@link #TREND_POINTS}. */
    public static String trend(List<Double> newestFirst) {
        int size = Math.min(WINDOW, newestFirst.size());
        if (size < TREND_POINTS) return null;
        int half = size / 2;
        double recent = mean(newestFirst.subList(0, half)), older = mean(newestFirst.subList(half, size));
        if (recent - older > TREND_EPSILON) return UP;
        return older - recent > TREND_EPSILON ? DOWN : FLAT;
    }

    private static double mean(List<Double> values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }
}
